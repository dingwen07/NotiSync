#define _DARWIN_C_SOURCE

#include <ctype.h>
#include <dlfcn.h>
#include <errno.h>
#include <dirent.h>
#include <jni.h>
#include <limits.h>
#include <mach-o/dyld.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

typedef jint (*CreateJavaVm)(JavaVM **vm, void **environment, void *arguments);

typedef struct {
    char **items;
    size_t count;
    size_t capacity;
} StringList;

#define DETACH_SESSION_ENV "NOTISYNC_INTERNAL_DETACH_SESSION"
#define DETACH_SESSION_VALUE "1"

static void fail(const char *message) {
    fprintf(stderr, "%s: %s\n", "notisync-launcher", message);
    exit(1);
}

static void fail_path(const char *message, const char *path) {
    fprintf(stderr, "%s: %s: %s\n", "notisync-launcher", message, path);
    exit(1);
}

static void fail_errno(const char *message) {
    fprintf(stderr, "%s: %s: %s\n", "notisync-launcher", message, strerror(errno));
    exit(1);
}

static void detach_session_if_requested(const char *name) {
    const char *requested = getenv(DETACH_SESSION_ENV);
    if (strcmp(name, "notisyncd") != 0 || requested == NULL) return;
    if (strcmp(requested, DETACH_SESSION_VALUE) != 0) {
        fail("invalid internal detach-session request");
    }
    if (setsid() < 0) fail_errno("cannot create the daemon session");
    if (unsetenv(DETACH_SESSION_ENV) != 0) fail_errno("cannot clear the detach-session request");
}

static char *duplicate_string(const char *value) {
    char *copy = strdup(value);
    if (copy == NULL) fail("out of memory");
    return copy;
}

static char *format_path(const char *first, const char *second) {
    size_t length = strlen(first) + strlen(second) + 2;
    char *result = malloc(length);
    if (result == NULL) fail("out of memory");
    snprintf(result, length, "%s/%s", first, second);
    return result;
}

static void list_add_owned(StringList *list, char *value) {
    if (list->count == list->capacity) {
        size_t next_capacity = list->capacity == 0 ? 16 : list->capacity * 2;
        char **next = realloc(list->items, next_capacity * sizeof(char *));
        if (next == NULL) fail("out of memory");
        list->items = next;
        list->capacity = next_capacity;
    }
    list->items[list->count++] = value;
}

static void list_add(StringList *list, const char *value) {
    list_add_owned(list, duplicate_string(value));
}

static void word_append(char **word, size_t *length, size_t *capacity, char value) {
    if (*length + 1 >= *capacity) {
        size_t next_capacity = *capacity == 0 ? 32 : *capacity * 2;
        char *next = realloc(*word, next_capacity);
        if (next == NULL) fail("out of memory");
        *word = next;
        *capacity = next_capacity;
    }
    (*word)[(*length)++] = value;
}

/* Match Gradle's launcher contract closely: whitespace separates arguments, quotes group them,
 * and backslashes escape the next byte. No shell expansion or command execution is performed. */
static void append_option_string(StringList *arguments, const char *options, const char *variable) {
    if (options == NULL || options[0] == '\0') return;
    const char *cursor = options;
    while (*cursor != '\0') {
        while (isspace((unsigned char)*cursor)) cursor++;
        if (*cursor == '\0') break;

        char quote = '\0';
        bool started = false;
        char *word = NULL;
        size_t length = 0;
        size_t capacity = 0;
        while (*cursor != '\0') {
            char current = *cursor;
            if (quote == '\0' && isspace((unsigned char)current)) break;
            started = true;
            cursor++;
            if (current == '\\' && quote != '\'') {
                if (*cursor == '\0') {
                    fprintf(stderr, "notisync-launcher: trailing backslash in %s\n", variable);
                    exit(1);
                }
                word_append(&word, &length, &capacity, *cursor++);
            } else if (current == '\'' || current == '"') {
                if (quote == '\0') quote = current;
                else if (quote == current) quote = '\0';
                else word_append(&word, &length, &capacity, current);
            } else {
                word_append(&word, &length, &capacity, current);
            }
        }
        if (quote != '\0') {
            fprintf(stderr, "notisync-launcher: unmatched quote in %s\n", variable);
            exit(1);
        }
        if (started) {
            word_append(&word, &length, &capacity, '\0');
            list_add_owned(arguments, word);
        } else {
            free(word);
        }
        while (isspace((unsigned char)*cursor)) cursor++;
    }
}

static bool is_regular_file(const char *path) {
    struct stat status;
    return stat(path, &status) == 0 && S_ISREG(status.st_mode);
}

static char *parent_directory(const char *path) {
    char *result = duplicate_string(path);
    char *slash = strrchr(result, '/');
    if (slash == NULL) {
        free(result);
        return duplicate_string(".");
    }
    if (slash == result) slash[1] = '\0';
    else *slash = '\0';
    return result;
}

static char *executable_path(void) {
    uint32_t size = PATH_MAX;
    char *raw = malloc(size);
    if (raw == NULL) fail("out of memory");
    if (_NSGetExecutablePath(raw, &size) != 0) {
        char *larger = realloc(raw, size);
        if (larger == NULL) fail("out of memory");
        raw = larger;
        if (_NSGetExecutablePath(raw, &size) != 0) fail("cannot determine executable path");
    }
    char *resolved = realpath(raw, NULL);
    free(raw);
    if (resolved == NULL) fail("cannot resolve executable path");
    return resolved;
}

static char *libjvm_for_home(const char *home) {
    if (home == NULL || home[0] == '\0') return NULL;
    const char *suffixes[] = { "lib/server/libjvm.dylib", "jre/lib/server/libjvm.dylib" };
    for (size_t index = 0; index < sizeof(suffixes) / sizeof(suffixes[0]); index++) {
        char *candidate = format_path(home, suffixes[index]);
        if (is_regular_file(candidate)) return candidate;
        free(candidate);
    }
    return NULL;
}

static char *java_home_command(void) {
    FILE *pipe = popen("/usr/libexec/java_home -v 21 2>/dev/null", "r");
    if (pipe == NULL) return NULL;
    char buffer[PATH_MAX];
    char *result = NULL;
    if (fgets(buffer, sizeof(buffer), pipe) != NULL) {
        buffer[strcspn(buffer, "\r\n")] = '\0';
        if (buffer[0] != '\0') result = duplicate_string(buffer);
    }
    pclose(pipe);
    return result;
}

static char *java_home_from_path(void) {
    const char *path = getenv("PATH");
    if (path == NULL) return NULL;
    char *copy = duplicate_string(path);
    char *save = NULL;
    for (char *entry = strtok_r(copy, ":", &save); entry != NULL; entry = strtok_r(NULL, ":", &save)) {
        if (entry[0] == '\0') entry = ".";
        char *candidate = format_path(entry, "java");
        if (access(candidate, X_OK) == 0) {
            char *resolved = realpath(candidate, NULL);
            if (resolved != NULL) {
                char *bin = parent_directory(resolved);
                char *home = parent_directory(bin);
                free(bin);
                free(resolved);
                char *jvm = libjvm_for_home(home);
                if (jvm != NULL) {
                    free(jvm);
                    free(candidate);
                    free(copy);
                    return home;
                }
                free(home);
            }
        }
        free(candidate);
    }
    free(copy);
    return NULL;
}

static char *find_libjvm(void) {
    const char *configured[] = { getenv("NOTISYNC_JAVA_HOME"), getenv("JAVA_HOME") };
    for (size_t index = 0; index < sizeof(configured) / sizeof(configured[0]); index++) {
        char *candidate = libjvm_for_home(configured[index]);
        if (candidate != NULL) return candidate;
    }

    char *home = java_home_command();
    char *candidate = libjvm_for_home(home);
    free(home);
    if (candidate != NULL) return candidate;

    home = java_home_from_path();
    candidate = libjvm_for_home(home);
    free(home);
    return candidate;
}

static const char *main_class_for(const char *name) {
    if (strcmp(name, "notisyncd") == 0) return "net.extrawdw.notisync.daemon.NotisyncdMainKt";
    if (strcmp(name, "nsrun") == 0) return "net.extrawdw.notisync.run.NSRunMainKt";
    if (strcmp(name, "notisync") == 0) return "net.extrawdw.notisync.cli.NotisyncMainKt";
    return NULL;
}

static const char *options_variable_for(const char *name) {
    if (strcmp(name, "notisyncd") == 0) return "NOTISYNCD_OPTS";
    if (strcmp(name, "nsrun") == 0) return "NSRUN_OPTS";
    return "NOTISYNC_OPTS";
}

static bool has_jar_suffix(const char *name) {
    size_t length = strlen(name);
    return length > 4 && strcmp(name + length - 4, ".jar") == 0;
}

static char *distribution_classpath(const char *lib_directory) {
    struct dirent **entries = NULL;
    int count = scandir(lib_directory, &entries, NULL, alphasort);
    if (count < 0) fail_path("cannot read distribution library directory", lib_directory);

    size_t length = 0;
    size_t jars = 0;
    for (int index = 0; index < count; index++) {
        if (has_jar_suffix(entries[index]->d_name)) {
            length += strlen(lib_directory) + strlen(entries[index]->d_name) + 2;
            jars++;
        }
    }
    if (jars == 0) fail_path("distribution contains no JAR files", lib_directory);
    char *classpath = malloc(length + 1);
    if (classpath == NULL) fail("out of memory");
    classpath[0] = '\0';
    size_t added = 0;
    for (int index = 0; index < count; index++) {
        if (has_jar_suffix(entries[index]->d_name)) {
            if (added++ > 0) strcat(classpath, ":");
            strcat(classpath, lib_directory);
            strcat(classpath, "/");
            strcat(classpath, entries[index]->d_name);
        }
        free(entries[index]);
    }
    free(entries);
    return classpath;
}

static char *property(const char *name, const char *value) {
    size_t length = strlen(name) + strlen(value) + 4;
    char *result = malloc(length);
    if (result == NULL) fail("out of memory");
    snprintf(result, length, "-D%s=%s", name, value);
    return result;
}

static char *internal_class_name(const char *class_name) {
    char *result = duplicate_string(class_name);
    for (char *cursor = result; *cursor != '\0'; cursor++) {
        if (*cursor == '.') *cursor = '/';
    }
    return result;
}

static int describe_exception(JNIEnv *environment, const char *context) {
    if (!(*environment)->ExceptionCheck(environment)) return 0;
    fprintf(stderr, "%s: %s\n", "notisync-launcher", context);
    (*environment)->ExceptionDescribe(environment);
    (*environment)->ExceptionClear(environment);
    return 1;
}

static jstring new_utf8_string(
    JNIEnv *environment,
    jclass string_type,
    jmethodID utf8_constructor,
    jstring utf8_name,
    const char *value
) {
    size_t length = strlen(value);
    if (length > INT32_MAX) fail("application argument is too long");
    jbyteArray bytes = (*environment)->NewByteArray(environment, (jsize)length);
    if (bytes == NULL) return NULL;
    if (length > 0) {
        (*environment)->SetByteArrayRegion(
            environment,
            bytes,
            0,
            (jsize)length,
            (const jbyte *)value
        );
    }
    jobject result = (*environment)->NewObject(
        environment,
        string_type,
        utf8_constructor,
        bytes,
        utf8_name
    );
    (*environment)->DeleteLocalRef(environment, bytes);
    return (jstring)result;
}

int main(int argc, char **argv) {
    char *executable = executable_path();
    const char *name = strrchr(executable, '/');
    name = name == NULL ? executable : name + 1;
    const char *main_class = main_class_for(name);
    if (main_class == NULL) fail_path("unknown launcher name", name);
    detach_session_if_requested(name);

    char *bin_directory = parent_directory(executable);
    char *application_home = parent_directory(bin_directory);
    char *lib_directory = format_path(application_home, "lib");
    char *classpath = distribution_classpath(lib_directory);
    free(application_home);
    free(bin_directory);

    char *libjvm = find_libjvm();
    if (libjvm == NULL) {
        fail("Java 21 was not found; set JAVA_HOME or NOTISYNC_JAVA_HOME to a JDK 21 installation");
    }
    void *library = dlopen(libjvm, RTLD_NOW | RTLD_GLOBAL);
    if (library == NULL) {
        fprintf(stderr, "notisync-launcher: cannot load %s: %s\n", libjvm, dlerror());
        return 1;
    }
    CreateJavaVm create_vm = (CreateJavaVm)dlsym(library, "JNI_CreateJavaVM");
    if (create_vm == NULL) fail("the selected Java runtime does not export JNI_CreateJavaVM");

    StringList option_strings = {0};
    list_add(&option_strings, "--enable-native-access=ALL-UNNAMED");
    append_option_string(&option_strings, getenv("JAVA_OPTS"), "JAVA_OPTS");
    const char *options_variable = options_variable_for(name);
    append_option_string(&option_strings, getenv(options_variable), options_variable);
    const char *data_directory = getenv("NOTISYNC_DATA_DIR");
    if (data_directory != NULL && data_directory[0] != '\0') {
        list_add_owned(&option_strings, property("notisync.dataDir", data_directory));
    }
    list_add_owned(&option_strings, property("java.class.path", classpath));
    list_add_owned(&option_strings, property("sun.java.command", name));
    list_add(&option_strings, "-Dsun.java.launcher=SUN_STANDARD");

    JavaVMOption *options = calloc(option_strings.count, sizeof(JavaVMOption));
    if (options == NULL) fail("out of memory");
    for (size_t index = 0; index < option_strings.count; index++) {
        options[index].optionString = option_strings.items[index];
    }
    JavaVMInitArgs vm_arguments = {
        .version = JNI_VERSION_1_8,
        .nOptions = (jint)option_strings.count,
        .options = options,
        .ignoreUnrecognized = JNI_FALSE,
    };
    JavaVM *vm = NULL;
    JNIEnv *environment = NULL;
    jint created = create_vm(&vm, (void **)&environment, &vm_arguments);
    if (created != JNI_OK || environment == NULL) {
        fprintf(stderr, "notisync-launcher: could not create Java VM (JNI error %d)\n", created);
        return 1;
    }

    char *internal_name = internal_class_name(main_class);
    jclass main_type = (*environment)->FindClass(environment, internal_name);
    free(internal_name);
    if (describe_exception(environment, "cannot load application main class") != 0 || main_type == NULL) return 1;
    jmethodID main_method = (*environment)->GetStaticMethodID(
        environment,
        main_type,
        "main",
        "([Ljava/lang/String;)V"
    );
    if (describe_exception(environment, "cannot find application main method") != 0 || main_method == NULL) return 1;
    jclass string_type = (*environment)->FindClass(environment, "java/lang/String");
    if (describe_exception(environment, "cannot load java.lang.String") != 0 || string_type == NULL) return 1;
    jmethodID utf8_constructor = (*environment)->GetMethodID(
        environment,
        string_type,
        "<init>",
        "([BLjava/lang/String;)V"
    );
    if (describe_exception(environment, "cannot find the UTF-8 String constructor") != 0 || utf8_constructor == NULL) {
        return 1;
    }
    jstring utf8_name = (*environment)->NewStringUTF(environment, "UTF-8");
    if (describe_exception(environment, "cannot allocate the UTF-8 charset name") != 0 || utf8_name == NULL) return 1;
    jobjectArray application_arguments = (*environment)->NewObjectArray(
        environment,
        argc - 1,
        string_type,
        NULL
    );
    if (describe_exception(environment, "cannot allocate application arguments") != 0) return 1;
    for (int index = 1; index < argc; index++) {
        jstring argument = new_utf8_string(environment, string_type, utf8_constructor, utf8_name, argv[index]);
        if (describe_exception(environment, "cannot decode an application argument") != 0) return 1;
        (*environment)->SetObjectArrayElement(environment, application_arguments, index - 1, argument);
        (*environment)->DeleteLocalRef(environment, argument);
        if (describe_exception(environment, "cannot populate application arguments") != 0) return 1;
    }
    (*environment)->DeleteLocalRef(environment, utf8_name);
    (*environment)->CallStaticVoidMethod(environment, main_type, main_method, application_arguments);
    int result = describe_exception(environment, "application terminated with an uncaught exception");
    if (result == 0) (*vm)->DestroyJavaVM(vm);
    dlclose(library);
    free(libjvm);
    free(lib_directory);
    free(classpath);
    free(executable);
    return result;
}
