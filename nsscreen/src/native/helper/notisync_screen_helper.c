/*
 * NotiSync attach-only Android screen viewer.
 *
 * The media framing, packet merger, control messages and device messages are
 * derived from scrcpy 4.1 (Apache-2.0), commit
 * 2926c06c5dc3064ae6d8db706f1a98a37cfcf3f0.  This process never sees LAN
 * addresses or session keys: it only connects to private AF_UNIX sockets
 * created by nsscreen after both TLS channels have authenticated.
 */

#define _GNU_SOURCE
#if defined(__APPLE__)
#define _DARWIN_C_SOURCE
#endif
#define _POSIX_C_SOURCE 200809L

#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <limits.h>
#include <math.h>
#include <pthread.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#if defined(__APPLE__)
#include <libproc.h>
#endif

#if !defined(NS_FORCE_SDL2) && defined(__has_include) && __has_include(<SDL3/SDL.h>)
#define NS_SDL3 1
#include <SDL3/SDL.h>
#else
#define NS_SDL3 0
#include <SDL2/SDL.h>
#endif

#include <libavcodec/avcodec.h>
#include <libavutil/error.h>
#include <libavutil/frame.h>
#include <libavutil/mem.h>
#include <libavutil/pixfmt.h>
#include <libswscale/swscale.h>

#if !NS_SDL3
#define SDL_EVENT_QUIT SDL_QUIT
#define SDL_EVENT_WINDOW_CLOSE_REQUESTED SDL_WINDOWEVENT
#define SDL_EVENT_KEY_DOWN SDL_KEYDOWN
#define SDL_EVENT_KEY_UP SDL_KEYUP
#define SDL_EVENT_TEXT_INPUT SDL_TEXTINPUT
#define SDL_EVENT_MOUSE_MOTION SDL_MOUSEMOTION
#define SDL_EVENT_MOUSE_BUTTON_DOWN SDL_MOUSEBUTTONDOWN
#define SDL_EVENT_MOUSE_BUTTON_UP SDL_MOUSEBUTTONUP
#define SDL_EVENT_MOUSE_WHEEL SDL_MOUSEWHEEL
#define SDL_EVENT_FINGER_DOWN SDL_FINGERDOWN
#define SDL_EVENT_FINGER_UP SDL_FINGERUP
#define SDL_EVENT_FINGER_MOTION SDL_FINGERMOTION
#define SDL_EVENT_FINGER_CANCELED UINT32_C(0x7ffffffe)
#define SDL_EVENT_CLIPBOARD_UPDATE SDL_CLIPBOARDUPDATE
#define SDL_KMOD_CTRL KMOD_CTRL
#define SDL_KMOD_SHIFT KMOD_SHIFT
#define SDL_KMOD_ALT KMOD_ALT
#define SDL_KMOD_GUI KMOD_GUI
#define SDL_KMOD_LCTRL KMOD_LCTRL
#define SDL_KMOD_RCTRL KMOD_RCTRL
#define SDL_KMOD_LSHIFT KMOD_LSHIFT
#define SDL_KMOD_RSHIFT KMOD_RSHIFT
#define SDL_KMOD_LALT KMOD_LALT
#define SDL_KMOD_RALT KMOD_RALT
#define SDL_KMOD_LGUI KMOD_LGUI
#define SDL_KMOD_RGUI KMOD_RGUI
#define SDL_WINDOW_HIGH_PIXEL_DENSITY SDL_WINDOW_ALLOW_HIGHDPI
#define NS_SDLK_A SDLK_a
#define NS_SDLK_Z SDLK_z
#else
#define NS_SDLK_A SDLK_A
#define NS_SDLK_Z SDLK_Z
#endif

#define NS_EXIT_USAGE 2
#define NS_EXIT_RUNTIME 3
#define NS_PACKET_HEADER_SIZE 12
#define NS_MAX_PACKET_SIZE (16U * 1024U * 1024U)
#define NS_MAX_VIDEO_DIMENSION 8192U
#define NS_MAX_VIDEO_PIXELS (16U * 1024U * 1024U)
/* A valid maximum-sized 4:4:4 frame is below this limit.  Keep FFmpeg from
 * honoring a hostile coded-stream request for a much larger single buffer. */
#define NS_MAX_FFMPEG_SINGLE_ALLOCATION (128U * 1024U * 1024U)
#define NS_MAX_CONTROL_CLIPBOARD_WIRE ((1U << 18) - 14U)
#define NS_MAX_DEVICE_CLIPBOARD_WIRE ((1U << 18) - 5U)
#define NS_DEFAULT_CLIPBOARD_LIMIT (1U << 18)
#define NS_CONTROL_QUEUE_MAX_MESSAGES 1024U
#define NS_CONTROL_QUEUE_MAX_BYTES (1U << 20)
#define NS_CLIPBOARD_REFLECTION_TIMEOUT_MS INT64_C(10000)
#define NS_LOCAL_CLIPBOARD_ACK_TIMEOUT_MS INT64_C(10000)
#define NS_MAX_PENDING_CLIPBOARD_REFLECTIONS 8U
#define NS_MAX_ACTIVE_TOUCHES 16U
#define NS_HELPER_CHALLENGE_BYTES 32U
#define NS_HELPER_AUTH_FRAME_BYTES (4U + 1U + 8U + NS_HELPER_CHALLENGE_BYTES)
#define NS_HELPER_VIDEO_CHANNEL 1U
#define NS_HELPER_CONTROL_CHANNEL 2U
#define NS_POINTER_ID_MOUSE UINT64_MAX
#define NS_CODEC_H264 UINT32_C(0x68323634)
#define NS_CODEC_H265 UINT32_C(0x68323635)
#define NS_CODEC_AV1 UINT32_C(0x00617631)
#define NS_PACKET_FLAG_CONFIG (UINT64_C(1) << 62)
#define NS_PACKET_FLAG_KEY_FRAME (UINT64_C(1) << 61)
#define NS_PACKET_PTS_MASK (NS_PACKET_FLAG_KEY_FRAME - 1)
#define NS_FUNCTION_BAR_HEIGHT 64.0f
#define NS_FUNCTION_BUTTON_COUNT 4
#define NS_KEYCODE_BACK_OR_SCREEN_ON (-2)
#define NS_KEYCODE_TOGGLE_POWER (-3)

enum ns_control_type {
    NS_CONTROL_INJECT_KEYCODE = 0,
    NS_CONTROL_INJECT_TEXT = 1,
    NS_CONTROL_INJECT_TOUCH = 2,
    NS_CONTROL_INJECT_SCROLL = 3,
    NS_CONTROL_BACK_OR_SCREEN_ON = 4,
    NS_CONTROL_SET_CLIPBOARD = 9,
    NS_CONTROL_TOGGLE_POWER = 64,
};

enum ns_function_button {
    NS_FUNCTION_NONE = -1,
    NS_FUNCTION_BACK = 0,
    NS_FUNCTION_HOME = 1,
    NS_FUNCTION_RECENTS = 2,
    NS_FUNCTION_POWER = 3,
};

enum ns_android_action {
    NS_ACTION_DOWN = 0,
    NS_ACTION_UP = 1,
    NS_ACTION_MOVE = 2,
    NS_ACTION_CANCEL = 3,
};

enum ns_control_coalesce_kind {
    NS_COALESCE_NONE,
    NS_COALESCE_TOUCH_MOVE,
    NS_COALESCE_SCROLL,
    NS_COALESCE_CLIPBOARD,
};

struct ns_control_message {
    struct ns_control_message *next;
    uint8_t *data;
    size_t length;
    enum ns_control_coalesce_kind coalesce_kind;
    uint64_t coalesce_key;
    uint64_t clipboard_sequence;
    bool essential;
};

struct ns_options {
    const char *video_socket;
    const char *control_socket;
    const char *title;
    uint32_t codec;
    size_t max_clipboard_bytes;
    bool allow_control;
    bool allow_clipboard;
    bool control_option_set;
    bool clipboard_option_set;
    bool self_test;
    bool check_runtime;
};

struct ns_frame {
    uint8_t *rgba;
    int width;
    int height;
    int pitch;
    uint64_t video_generation;
};

struct ns_input_state {
    bool mouse_active;
    uint64_t touches[NS_MAX_ACTIVE_TOUCHES];
    size_t touch_count;
};

struct ns_view_layout {
    SDL_FRect video_bounds;
    SDL_FRect video_destination;
    SDL_FRect function_bar;
    SDL_FRect function_buttons[NS_FUNCTION_BUTTON_COUNT];
};

struct ns_app {
    int video_fd;
    int control_fd;
    uint32_t expected_codec;
    size_t max_clipboard_bytes;
    bool allow_control;
    bool allow_clipboard;
    atomic_bool running;
    atomic_int worker_failure;
    atomic_uint_fast64_t acknowledged_clipboard_sequence;
    atomic_uint_fast64_t video_generation;
    pthread_t video_thread;
    pthread_t control_thread;
    pthread_t control_writer_thread;
    bool video_thread_started;
    bool control_thread_started;
    bool control_writer_thread_started;
    pthread_mutex_t frame_mutex;
    pthread_mutex_t control_queue_mutex;
    pthread_cond_t control_queue_cond;
    pthread_mutex_t clipboard_mutex;
    struct ns_frame *pending_frame;
    struct ns_control_message *control_queue_head;
    struct ns_control_message *control_queue_tail;
    size_t control_queue_count;
    size_t control_queue_bytes;
    bool control_queue_stopping;
    char *pending_remote_clipboard;
    bool remote_clipboard_event_pending;
    uint32_t frame_event;
    uint32_t remote_clipboard_event;
    uint32_t disconnected_event;
    uint16_t source_width;
    uint16_t source_height;
    uint64_t applied_video_generation;
    bool pointer_input_ready;
    struct ns_input_state input_state;
    enum ns_function_button pressed_function_button;
    uint64_t clipboard_sequence;
    uint64_t pending_local_clipboard_sequence;
    char *pending_local_clipboard;
    int64_t pending_local_clipboard_deadline_ms;
    char *remote_clipboard_reflection;
    unsigned int pending_remote_clipboard_reflections;
    int64_t remote_clipboard_reflection_deadline_ms;
};

static volatile sig_atomic_t ns_stop_requested;

static void
ns_signal_handler(int signal_number) {
    (void) signal_number;
    ns_stop_requested = 1;
}

static void
ns_usage(FILE *stream, const char *program) {
    fprintf(stream,
            "Usage: %s --video-socket PATH --control-socket PATH "
            "--codec h264|h265|av1 --control true|false "
            "--clipboard true|false [--title TEXT] "
            "[--max-clipboard-bytes N]\n",
            program);
}

static bool
ns_parse_bool(const char *text, bool *value) {
    if (!strcmp(text, "true")) {
        *value = true;
        return true;
    }
    if (!strcmp(text, "false")) {
        *value = false;
        return true;
    }
    return false;
}

static bool
ns_parse_size(const char *text, size_t *value) {
    char *end = NULL;
    errno = 0;
    unsigned long long parsed = strtoull(text, &end, 10);
    if (errno || !end || *end || !parsed || parsed > NS_DEFAULT_CLIPBOARD_LIMIT) {
        return false;
    }
    *value = (size_t) parsed;
    return true;
}

static bool
ns_parse_options(int argc, char **argv, struct ns_options *options) {
    *options = (struct ns_options) {
        .title = "NotiSync Screen",
        .max_clipboard_bytes = NS_DEFAULT_CLIPBOARD_LIMIT,
    };

    for (int i = 1; i < argc; ++i) {
        if (!strcmp(argv[i], "--self-test")) {
            options->self_test = true;
        } else if (!strcmp(argv[i], "--check-runtime")) {
            options->check_runtime = true;
        } else if (!strcmp(argv[i], "--video-socket") && i + 1 < argc) {
            options->video_socket = argv[++i];
        } else if (!strcmp(argv[i], "--control-socket") && i + 1 < argc) {
            options->control_socket = argv[++i];
        } else if (!strcmp(argv[i], "--title") && i + 1 < argc) {
            options->title = argv[++i];
        } else if (!strcmp(argv[i], "--max-clipboard-bytes") && i + 1 < argc) {
            if (!ns_parse_size(argv[++i], &options->max_clipboard_bytes)) {
                return false;
            }
        } else if (!strcmp(argv[i], "--control") && i + 1 < argc) {
            if (options->control_option_set
                    || !ns_parse_bool(argv[++i], &options->allow_control)) {
                return false;
            }
            options->control_option_set = true;
        } else if (!strcmp(argv[i], "--clipboard") && i + 1 < argc) {
            if (options->clipboard_option_set
                    || !ns_parse_bool(argv[++i], &options->allow_clipboard)) {
                return false;
            }
            options->clipboard_option_set = true;
        } else if (!strcmp(argv[i], "--codec") && i + 1 < argc) {
            const char *codec = argv[++i];
            if (!strcmp(codec, "h264")) {
                options->codec = NS_CODEC_H264;
            } else if (!strcmp(codec, "h265")) {
                options->codec = NS_CODEC_H265;
            } else if (!strcmp(codec, "av1")) {
                options->codec = NS_CODEC_AV1;
            } else {
                return false;
            }
        } else {
            return false;
        }
    }

    if (options->self_test || options->check_runtime) {
        return argc == 2;
    }
    return options->video_socket && options->control_socket && options->codec
        && options->control_option_set && options->clipboard_option_set;
}

static uint16_t
ns_read16be(const uint8_t *data) {
    return (uint16_t) ((uint16_t) data[0] << 8 | data[1]);
}

static uint32_t
ns_read32be(const uint8_t *data) {
    return (uint32_t) data[0] << 24 | (uint32_t) data[1] << 16
         | (uint32_t) data[2] << 8 | data[3];
}

static uint64_t
ns_read64be(const uint8_t *data) {
    return (uint64_t) ns_read32be(data) << 32 | ns_read32be(data + 4);
}

static void
ns_write16be(uint8_t *data, uint16_t value) {
    data[0] = (uint8_t) (value >> 8);
    data[1] = (uint8_t) value;
}

static void
ns_write32be(uint8_t *data, uint32_t value) {
    data[0] = (uint8_t) (value >> 24);
    data[1] = (uint8_t) (value >> 16);
    data[2] = (uint8_t) (value >> 8);
    data[3] = (uint8_t) value;
}

static void
ns_write64be(uint8_t *data, uint64_t value) {
    ns_write32be(data, (uint32_t) (value >> 32));
    ns_write32be(data + 4, (uint32_t) value);
}

static bool
ns_utf8_valid(const uint8_t *text, size_t length) {
    for (size_t i = 0; i < length;) {
        uint8_t first = text[i++];
        if (!first) {
            return false;
        }
        if (first < 0x80) {
            continue;
        }

        uint32_t codepoint;
        unsigned remaining;
        if (first >= 0xC2 && first <= 0xDF) {
            codepoint = first & 0x1F;
            remaining = 1;
        } else if (first >= 0xE0 && first <= 0xEF) {
            codepoint = first & 0x0F;
            remaining = 2;
        } else if (first >= 0xF0 && first <= 0xF4) {
            codepoint = first & 0x07;
            remaining = 3;
        } else {
            return false;
        }
        if (i + remaining > length) {
            return false;
        }
        for (unsigned j = 0; j < remaining; ++j) {
            uint8_t continuation = text[i++];
            if ((continuation & 0xC0) != 0x80) {
                return false;
            }
            codepoint = codepoint << 6 | (continuation & 0x3F);
        }
        if ((remaining == 2 && codepoint < 0x800)
                || (remaining == 3 && codepoint < 0x10000)
                || (codepoint >= 0xD800 && codepoint <= 0xDFFF)
                || codepoint > 0x10FFFF) {
            return false;
        }
    }
    return true;
}

static bool
ns_recv_all(int fd, void *target, size_t length) {
    uint8_t *data = target;
    while (length) {
        ssize_t received = recv(fd, data, length, 0);
        if (!received) {
            return false;
        }
        if (received < 0) {
            if (errno == EINTR) {
                continue;
            }
            return false;
        }
        data += received;
        length -= (size_t) received;
    }
    return true;
}

static bool
ns_read_all_fd(int fd, void *target, size_t length) {
    uint8_t *data = target;
    while (length) {
        ssize_t received = read(fd, data, length);
        if (!received) return false;
        if (received < 0) {
            if (errno == EINTR) continue;
            return false;
        }
        data += received;
        length -= (size_t) received;
    }
    return true;
}

static bool
ns_send_fd_all(int fd, const void *source, size_t length) {
    const uint8_t *data = source;
    while (length) {
#ifdef MSG_NOSIGNAL
        ssize_t sent = send(fd, data, length, MSG_NOSIGNAL);
#else
        ssize_t sent = send(fd, data, length, 0);
#endif
        if (sent < 0) {
            if (errno == EINTR) continue;
            return false;
        }
        if (!sent) return false;
        data += sent;
        length -= (size_t) sent;
    }
    return true;
}

static bool
ns_send_all(struct ns_app *app, const void *source, size_t length) {
    const uint8_t *data = source;
    bool success = true;
    while (length && atomic_load(&app->running)) {
#ifdef MSG_NOSIGNAL
        ssize_t sent = send(app->control_fd, data, length, MSG_NOSIGNAL);
#else
        ssize_t sent = send(app->control_fd, data, length, 0);
#endif
        if (sent < 0) {
            if (errno == EINTR) {
                continue;
            }
            success = false;
            break;
        }
        if (!sent) {
            success = false;
            break;
        }
        data += sent;
        length -= (size_t) sent;
    }
    return success && !length;
}

static int64_t
ns_monotonic_millis(void) {
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    return (int64_t) now.tv_sec * 1000 + now.tv_nsec / 1000000;
}

static bool
ns_private_socket_parent(const char *path) {
    if (!path || strlen(path) >= sizeof(((struct sockaddr_un *) 0)->sun_path)) {
        return false;
    }
    char parent[PATH_MAX];
    if (strlen(path) >= sizeof(parent)) {
        return false;
    }
    strcpy(parent, path);
    char *slash = strrchr(parent, '/');
    if (!slash) {
        return false;
    }
    if (slash == parent) {
        slash[1] = '\0';
    } else {
        *slash = '\0';
    }

    struct stat status;
    return !stat(parent, &status) && S_ISDIR(status.st_mode)
        && status.st_uid == getuid() && !(status.st_mode & 0077);
}

static bool
ns_peer_is_parent(int fd) {
    pid_t expected = getppid();
#if defined(__linux__)
    struct ucred credentials;
    socklen_t length = sizeof(credentials);
    return !getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &credentials, &length)
        && length == sizeof(credentials) && credentials.pid == expected
        && credentials.uid == getuid();
#elif defined(__APPLE__) && defined(LOCAL_PEERPID)
    pid_t peer = 0;
    socklen_t length = sizeof(peer);
    errno = 0;
    int result = getsockopt(fd, SOL_LOCAL, LOCAL_PEERPID, &peer, &length);
    int peer_errno = errno;
    if (result || length != sizeof(peer) || peer <= 1) {
        fprintf(stderr,
                "Could not verify Unix listener pid: helper=%d expected-parent=%d "
                "peer=%d length=%u errno=%d (%s)\n",
                getpid(), expected, peer, (unsigned) length, peer_errno,
                strerror(peer_errno));
        return false;
    }

    /* ProcessBuilder may place a short-lived launcher between this helper and
     * the JVM which owns the Unix listener. LOCAL_PEERPID identifies that
     * live JVM, while getppid() then identifies the intermediate launcher.
     * Accept only a verified live ancestor; the private stdin challenge still
     * authenticates this exact helper pid and channel back to the JVM. */
    pid_t ancestor = expected;
    for (unsigned depth = 0; ancestor > 1 && depth < 64; ++depth) {
        if (ancestor == peer) {
            if (peer != expected) {
                fprintf(stderr,
                        "Unix listener pid %d is verified ancestor of helper %d "
                        "(direct parent %d)\n",
                        peer, getpid(), expected);
            }
            return true;
        }
        struct proc_bsdinfo info;
        int count = proc_pidinfo(ancestor, PROC_PIDTBSDINFO, 0, &info,
                                 sizeof(info));
        if (count != sizeof(info) || !info.pbi_ppid
                || (pid_t) info.pbi_ppid == ancestor) {
            break;
        }
        ancestor = (pid_t) info.pbi_ppid;
    }
    fprintf(stderr,
            "Unix listener pid mismatch: helper=%d expected-parent=%d peer=%d "
            "errno=%d (%s)\n",
            getpid(), expected, peer, peer_errno, strerror(peer_errno));
    return false;
#else
    (void) fd;
    (void) expected;
    return false;
#endif
}

static int
ns_connect_unix(const char *path, const uint8_t *challenge,
                uint8_t channel_id) {
    if (!ns_private_socket_parent(path)) {
        fprintf(stderr, "Refusing non-private Unix socket directory for %s\n", path);
        return -1;
    }

    int64_t deadline = ns_monotonic_millis() + 10000;
    do {
        int fd = socket(AF_UNIX, SOCK_STREAM, 0);
        if (fd < 0) {
            return -1;
        }
        fcntl(fd, F_SETFD, FD_CLOEXEC);
#ifdef SO_NOSIGPIPE
        int enabled = 1;
        setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &enabled, sizeof(enabled));
#endif

        struct sockaddr_un address = { .sun_family = AF_UNIX };
        strcpy(address.sun_path, path);
        if (!connect(fd, (struct sockaddr *) &address, sizeof(address))) {
            if (!ns_peer_is_parent(fd)) {
                fprintf(stderr, "Refusing Unix socket not owned by helper parent\n");
                close(fd);
                return -1;
            }
            uint8_t authentication[NS_HELPER_AUTH_FRAME_BYTES];
            memcpy(authentication, "NSIP", 4);
            authentication[4] = channel_id;
            ns_write64be(authentication + 5, (uint64_t) getpid());
            memcpy(authentication + 13, challenge, NS_HELPER_CHALLENGE_BYTES);
            if (!ns_send_fd_all(fd, authentication, sizeof(authentication))) {
                close(fd);
                return -1;
            }
            return fd;
        }
        int saved_errno = errno;
        close(fd);
        if (saved_errno != ENOENT && saved_errno != ECONNREFUSED
                && saved_errno != EINTR) {
            errno = saved_errno;
            return -1;
        }
        struct timespec retry = { .tv_nsec = 50 * 1000 * 1000 };
        nanosleep(&retry, NULL);
    } while (ns_monotonic_millis() < deadline);

    errno = ETIMEDOUT;
    return -1;
}

static enum AVCodecID
ns_avcodec_id(uint32_t codec) {
    switch (codec) {
        case NS_CODEC_H264: return AV_CODEC_ID_H264;
        case NS_CODEC_H265: return AV_CODEC_ID_HEVC;
        case NS_CODEC_AV1: return AV_CODEC_ID_AV1;
        default: return AV_CODEC_ID_NONE;
    }
}

static void
ns_free_frame(struct ns_frame *frame) {
    if (frame) {
        free(frame->rgba);
        free(frame);
    }
}

static bool
ns_push_event(SDL_Event *event) {
#if NS_SDL3
    return SDL_PushEvent(event);
#else
    return SDL_PushEvent(event) > 0;
#endif
}

static void
ns_report_worker_failure(struct ns_app *app, int channel) {
    if (!atomic_load(&app->running)) return;

    int expected = 0;
    if (!atomic_compare_exchange_strong(&app->worker_failure, &expected, channel)) {
        return;
    }

    SDL_Event event;
    memset(&event, 0, sizeof(event));
    event.type = app->disconnected_event;
    event.user.code = channel;
    // The atomic failure is polled by the SDL thread, so a full or filtered
    // event queue cannot strand the viewer.
    ns_push_event(&event);
}

static void
ns_clear_pending_local_clipboard(struct ns_app *app) {
    free(app->pending_local_clipboard);
    app->pending_local_clipboard = NULL;
    app->pending_local_clipboard_sequence = 0;
    app->pending_local_clipboard_deadline_ms = 0;
}

static void
ns_control_message_free(struct ns_app *app, struct ns_control_message *message,
                        bool dropped) {
    if (dropped && message->coalesce_kind == NS_COALESCE_CLIPBOARD
            && message->clipboard_sequence
                == app->pending_local_clipboard_sequence) {
        ns_clear_pending_local_clipboard(app);
    }
    free(message->data);
    free(message);
}

static void
ns_control_queue_remove_locked(struct ns_app *app,
                               struct ns_control_message *previous,
                               struct ns_control_message *message,
                               bool dropped) {
    if (previous) {
        previous->next = message->next;
    } else {
        app->control_queue_head = message->next;
    }
    if (app->control_queue_tail == message) {
        app->control_queue_tail = previous;
    }
    --app->control_queue_count;
    app->control_queue_bytes -= message->length;
    ns_control_message_free(app, message, dropped);
}

static bool
ns_control_enqueue_owned(struct ns_app *app, uint8_t *data, size_t length,
                         bool essential,
                         enum ns_control_coalesce_kind coalesce_kind,
                         uint64_t coalesce_key, uint64_t clipboard_sequence) {
    if (!data || !length || length > NS_CONTROL_QUEUE_MAX_BYTES) {
        free(data);
        if (essential) ns_report_worker_failure(app, 2);
        return false;
    }

    struct ns_control_message *message = calloc(1, sizeof(*message));
    if (!message) {
        free(data);
        if (essential) ns_report_worker_failure(app, 2);
        return false;
    }
    message->data = data;
    message->length = length;
    message->essential = essential;
    message->coalesce_kind = coalesce_kind;
    message->coalesce_key = coalesce_key;
    message->clipboard_sequence = clipboard_sequence;

    bool capacity_failure = false;
    pthread_mutex_lock(&app->control_queue_mutex);
    if (app->control_queue_stopping || !atomic_load(&app->running)) {
        pthread_mutex_unlock(&app->control_queue_mutex);
        ns_control_message_free(app, message, false);
        return false;
    }

    if (coalesce_kind != NS_COALESCE_NONE) {
        struct ns_control_message *previous = NULL;
        struct ns_control_message *current = app->control_queue_head;
        while (current) {
            if (current->coalesce_kind == coalesce_kind
                    && current->coalesce_key == coalesce_key) {
                ns_control_queue_remove_locked(app, previous, current, true);
                break;
            }
            previous = current;
            current = current->next;
        }
    }

    while (app->control_queue_count >= NS_CONTROL_QUEUE_MAX_MESSAGES
            || app->control_queue_bytes + length > NS_CONTROL_QUEUE_MAX_BYTES) {
        struct ns_control_message *previous = NULL;
        struct ns_control_message *current = app->control_queue_head;
        while (current && current->essential) {
            previous = current;
            current = current->next;
        }
        if (!current) {
            capacity_failure = essential;
            pthread_mutex_unlock(&app->control_queue_mutex);
            ns_control_message_free(app, message, false);
            if (capacity_failure) ns_report_worker_failure(app, 2);
            return false;
        }
        ns_control_queue_remove_locked(app, previous, current, true);
    }

    if (essential) {
        // Keep essential transitions ordered with respect to one another, but
        // do not leave a key/up/cancel stranded behind coalescible traffic.
        struct ns_control_message *previous = NULL;
        struct ns_control_message *current = app->control_queue_head;
        while (current && current->essential) {
            previous = current;
            current = current->next;
        }
        message->next = current;
        if (previous) {
            previous->next = message;
        } else {
            app->control_queue_head = message;
        }
        if (!current) app->control_queue_tail = message;
    } else if (app->control_queue_tail) {
        app->control_queue_tail->next = message;
        app->control_queue_tail = message;
    } else {
        app->control_queue_head = message;
        app->control_queue_tail = message;
    }
    if (!app->control_queue_tail) app->control_queue_tail = message;
    ++app->control_queue_count;
    app->control_queue_bytes += length;
    pthread_cond_signal(&app->control_queue_cond);
    pthread_mutex_unlock(&app->control_queue_mutex);
    return true;
}

static bool
ns_control_enqueue_copy(struct ns_app *app, const void *source, size_t length,
                        bool essential,
                        enum ns_control_coalesce_kind coalesce_kind,
                        uint64_t coalesce_key) {
    uint8_t *copy = malloc(length);
    if (!copy) {
        if (essential) ns_report_worker_failure(app, 2);
        return false;
    }
    memcpy(copy, source, length);
    return ns_control_enqueue_owned(app, copy, length, essential, coalesce_kind,
                                    coalesce_key, 0);
}

static void
ns_control_drop_coalesced(struct ns_app *app,
                          enum ns_control_coalesce_kind coalesce_kind,
                          uint64_t coalesce_key) {
    pthread_mutex_lock(&app->control_queue_mutex);
    struct ns_control_message *previous = NULL;
    struct ns_control_message *current = app->control_queue_head;
    while (current) {
        if (current->coalesce_kind == coalesce_kind
                && current->coalesce_key == coalesce_key) {
            ns_control_queue_remove_locked(app, previous, current, true);
            break;
        }
        previous = current;
        current = current->next;
    }
    pthread_mutex_unlock(&app->control_queue_mutex);
}

static void
ns_control_drop_all_coalesced(struct ns_app *app,
                              enum ns_control_coalesce_kind coalesce_kind) {
    pthread_mutex_lock(&app->control_queue_mutex);
    struct ns_control_message *previous = NULL;
    struct ns_control_message *current = app->control_queue_head;
    while (current) {
        struct ns_control_message *next = current->next;
        if (current->coalesce_kind == coalesce_kind) {
            ns_control_queue_remove_locked(app, previous, current, true);
        } else {
            previous = current;
        }
        current = next;
    }
    pthread_mutex_unlock(&app->control_queue_mutex);
}

static void *
ns_control_writer_main(void *userdata) {
    struct ns_app *app = userdata;
    for (;;) {
        pthread_mutex_lock(&app->control_queue_mutex);
        while (!app->control_queue_head && !app->control_queue_stopping) {
            pthread_cond_wait(&app->control_queue_cond,
                              &app->control_queue_mutex);
        }
        if (app->control_queue_stopping) {
            pthread_mutex_unlock(&app->control_queue_mutex);
            break;
        }

        struct ns_control_message *message = app->control_queue_head;
        app->control_queue_head = message->next;
        if (!app->control_queue_head) app->control_queue_tail = NULL;
        --app->control_queue_count;
        app->control_queue_bytes -= message->length;
        pthread_mutex_unlock(&app->control_queue_mutex);

        bool sent = ns_send_all(app, message->data, message->length);
        ns_control_message_free(app, message, false);
        if (!sent) {
            if (atomic_load(&app->running)) ns_report_worker_failure(app, 2);
            break;
        }
    }
    return NULL;
}

static void
ns_control_queue_stop(struct ns_app *app) {
    pthread_mutex_lock(&app->control_queue_mutex);
    app->control_queue_stopping = true;
    pthread_cond_broadcast(&app->control_queue_cond);
    pthread_mutex_unlock(&app->control_queue_mutex);
}

static void
ns_control_queue_clear(struct ns_app *app) {
    pthread_mutex_lock(&app->control_queue_mutex);
    while (app->control_queue_head) {
        ns_control_queue_remove_locked(app, NULL, app->control_queue_head, true);
    }
    pthread_mutex_unlock(&app->control_queue_mutex);
}

static bool
ns_queue_remote_clipboard(struct ns_app *app, char *text) {
    bool needs_event;
    pthread_mutex_lock(&app->clipboard_mutex);
    free(app->pending_remote_clipboard);
    app->pending_remote_clipboard = text;
    needs_event = !app->remote_clipboard_event_pending;
    app->remote_clipboard_event_pending = true;
    pthread_mutex_unlock(&app->clipboard_mutex);

    if (!needs_event) return true;

    SDL_Event event;
    memset(&event, 0, sizeof(event));
    event.type = app->remote_clipboard_event;
    if (ns_push_event(&event)) return true;

    pthread_mutex_lock(&app->clipboard_mutex);
    free(app->pending_remote_clipboard);
    app->pending_remote_clipboard = NULL;
    app->remote_clipboard_event_pending = false;
    pthread_mutex_unlock(&app->clipboard_mutex);
    ns_report_worker_failure(app, 2);
    return false;
}

static char *
ns_take_remote_clipboard(struct ns_app *app) {
    pthread_mutex_lock(&app->clipboard_mutex);
    char *text = app->pending_remote_clipboard;
    app->pending_remote_clipboard = NULL;
    app->remote_clipboard_event_pending = false;
    pthread_mutex_unlock(&app->clipboard_mutex);
    return text;
}

static bool
ns_queue_frame(struct ns_app *app, struct ns_frame *frame) {
    pthread_mutex_lock(&app->frame_mutex);
    bool needs_event = !app->pending_frame;
    ns_free_frame(app->pending_frame);
    app->pending_frame = frame;
    pthread_mutex_unlock(&app->frame_mutex);

    if (needs_event) {
        SDL_Event event;
        memset(&event, 0, sizeof(event));
        event.type = app->frame_event;
        if (!ns_push_event(&event)) {
            pthread_mutex_lock(&app->frame_mutex);
            ns_free_frame(app->pending_frame);
            app->pending_frame = NULL;
            pthread_mutex_unlock(&app->frame_mutex);
            return false;
        }
    }
    return true;
}

static bool ns_send_global_cancel(struct ns_app *app);

static void
ns_input_state_reset(struct ns_input_state *state) {
    state->mouse_active = false;
    state->touch_count = 0;
}

static bool
ns_input_state_active(const struct ns_input_state *state) {
    return state->mouse_active || state->touch_count;
}

static size_t
ns_input_touch_index(const struct ns_input_state *state, uint64_t pointer_id) {
    for (size_t i = 0; i < state->touch_count; ++i) {
        if (state->touches[i] == pointer_id) return i;
    }
    return SIZE_MAX;
}

static bool
ns_input_touch_begin(struct ns_input_state *state, uint64_t pointer_id) {
    if (ns_input_touch_index(state, pointer_id) != SIZE_MAX
            || state->touch_count == NS_MAX_ACTIVE_TOUCHES) {
        return false;
    }
    state->touches[state->touch_count++] = pointer_id;
    return true;
}

static bool
ns_input_touch_active(const struct ns_input_state *state, uint64_t pointer_id) {
    return ns_input_touch_index(state, pointer_id) != SIZE_MAX;
}

static bool
ns_input_touch_end(struct ns_input_state *state, uint64_t pointer_id) {
    size_t index = ns_input_touch_index(state, pointer_id);
    if (index == SIZE_MAX) return false;
    state->touches[index] = state->touches[--state->touch_count];
    return true;
}

/* The decoder publishes a new generation before parsing frames following a
 * session-header size change.  The SDL thread clears all locally held
 * pointers before it processes another event, and does not resume coordinate
 * input until a frame from that exact generation has installed its mapper. */
static void
ns_reconcile_video_generation(struct ns_app *app) {
    uint64_t generation = atomic_load(&app->video_generation);
    if (generation == app->applied_video_generation) return;
    if (ns_input_state_active(&app->input_state)) {
        /* One ACTION_CANCEL ends the complete Android gesture. It must be queued
         * with the old mapper before local state moves to the new generation. */
        ns_send_global_cancel(app);
    }
    app->applied_video_generation = generation;
    app->pointer_input_ready = false;
    ns_input_state_reset(&app->input_state);
}

static bool
ns_pointer_input_ready(const struct ns_app *app) {
    return app->pointer_input_ready
        && app->applied_video_generation == atomic_load(&app->video_generation);
}

static bool
ns_decoder_packet_size_valid(size_t config_size, size_t media_size) {
    return config_size <= NS_MAX_PACKET_SIZE
        && media_size <= NS_MAX_PACKET_SIZE
        && config_size <= NS_MAX_PACKET_SIZE - media_size;
}

enum ns_decoder_packet_action {
    NS_DECODER_PACKET_ERROR = -1,
    NS_DECODER_PACKET_DROP = 0,
    NS_DECODER_PACKET_SEND = 1,
};

/* scrcpy's decoder never submits codec-config-only packets to FFmpeg. For
 * H.264/H.265, keep the config and prepend it to the next media packet so the
 * first access unit contains the parameter sets expected by the decoder. */
static enum ns_decoder_packet_action
ns_prepare_decoder_packet(bool h26x, bool is_config, AVPacket *packet,
                          uint8_t **config, size_t *config_size) {
    if (is_config) {
        if (h26x) {
            uint8_t *replacement = malloc((size_t) packet->size);
            if (!replacement) return NS_DECODER_PACKET_ERROR;
            memcpy(replacement, packet->data, (size_t) packet->size);
            free(*config);
            *config = replacement;
            *config_size = (size_t) packet->size;
        }
        return NS_DECODER_PACKET_DROP;
    }

    if (h26x && *config) {
        size_t media_size = (size_t) packet->size;
        if (!ns_decoder_packet_size_valid(*config_size, media_size)
                || *config_size > INT_MAX
                || av_grow_packet(packet, (int) *config_size) < 0) {
            return NS_DECODER_PACKET_ERROR;
        }
        memmove(packet->data + *config_size, packet->data, media_size);
        memcpy(packet->data, *config, *config_size);
        free(*config);
        *config = NULL;
        *config_size = 0;
    }

    return NS_DECODER_PACKET_SEND;
}

static bool
ns_self_test_decoder_packet_preparation(void) {
    static const uint8_t h264_config[] = { 0x00, 0x00, 0x00, 0x01, 0x67 };
    static const uint8_t h264_media[] = { 0x00, 0x00, 0x00, 0x01, 0x65, 0xaa };
    AVPacket *packet = av_packet_alloc();
    uint8_t *config = NULL;
    size_t config_size = 0;
    bool success = false;
    if (!packet) return false;

    if (av_new_packet(packet, (int) sizeof(h264_config)) < 0) goto cleanup;
    memcpy(packet->data, h264_config, sizeof(h264_config));
    if (ns_prepare_decoder_packet(true, true, packet, &config, &config_size)
            != NS_DECODER_PACKET_DROP
            || config_size != sizeof(h264_config)
            || memcmp(config, h264_config, sizeof(h264_config))) {
        goto cleanup;
    }
    av_packet_unref(packet);

    if (av_new_packet(packet, (int) sizeof(h264_media)) < 0) goto cleanup;
    memcpy(packet->data, h264_media, sizeof(h264_media));
    if (ns_prepare_decoder_packet(true, false, packet, &config, &config_size)
            != NS_DECODER_PACKET_SEND
            || config || config_size
            || packet->size != (int) (sizeof(h264_config) + sizeof(h264_media))
            || memcmp(packet->data, h264_config, sizeof(h264_config))
            || memcmp(packet->data + sizeof(h264_config), h264_media,
                      sizeof(h264_media))) {
        goto cleanup;
    }
    av_packet_unref(packet);

    /* Non-H.26x config packets are also metadata-only and must not be sent to
     * the decoder, but they are not merged into the next access unit. */
    if (av_new_packet(packet, 1) < 0) goto cleanup;
    packet->data[0] = 0x12;
    if (ns_prepare_decoder_packet(false, true, packet, &config, &config_size)
            != NS_DECODER_PACKET_DROP
            || config || config_size) {
        goto cleanup;
    }

    success = true;

cleanup:
    free(config);
    av_packet_free(&packet);
    return success;
}

static bool
ns_decode_available(struct ns_app *app, AVCodecContext *codec_context,
                    AVFrame *decoded, struct SwsContext **scaler,
                    uint64_t video_generation) {
    for (;;) {
        int result = avcodec_receive_frame(codec_context, decoded);
        if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) {
            return true;
        }
        if (result < 0 || decoded->width <= 0 || decoded->height <= 0
                || decoded->width > (int) NS_MAX_VIDEO_DIMENSION
                || decoded->height > (int) NS_MAX_VIDEO_DIMENSION
                || (uint64_t) decoded->width * decoded->height
                    > NS_MAX_VIDEO_PIXELS) {
            return false;
        }

        *scaler = sws_getCachedContext(*scaler, decoded->width, decoded->height,
                                       (enum AVPixelFormat) decoded->format,
                                       decoded->width, decoded->height,
                                       AV_PIX_FMT_RGBA, SWS_BILINEAR,
                                       NULL, NULL, NULL);
        if (!*scaler) {
            return false;
        }

        size_t pitch = (size_t) decoded->width * 4;
        if (pitch > INT_MAX || (size_t) decoded->height > SIZE_MAX / pitch) {
            return false;
        }
        struct ns_frame *frame = calloc(1, sizeof(*frame));
        if (!frame) {
            return false;
        }
        frame->rgba = malloc(pitch * (size_t) decoded->height);
        if (!frame->rgba) {
            free(frame);
            return false;
        }
        frame->width = decoded->width;
        frame->height = decoded->height;
        frame->pitch = (int) pitch;
        frame->video_generation = video_generation;
        uint8_t *outputs[] = { frame->rgba, NULL, NULL, NULL };
        int output_strides[] = { frame->pitch, 0, 0, 0 };
        int scaled_height = sws_scale(
            *scaler, (const uint8_t *const *) decoded->data,
            decoded->linesize, 0, decoded->height, outputs, output_strides);
        if (scaled_height != decoded->height) {
            ns_free_frame(frame);
            return false;
        }
        if (!ns_queue_frame(app, frame)) {
            return false;
        }
        av_frame_unref(decoded);
    }
}

static void *
ns_video_main(void *userdata) {
    struct ns_app *app = userdata;
    uint8_t codec_data[4];
    uint8_t header[NS_PACKET_HEADER_SIZE];
    AVCodecContext *codec_context = NULL;
    AVPacket *packet = NULL;
    AVFrame *decoded = NULL;
    struct SwsContext *scaler = NULL;
    uint8_t *config = NULL;
    size_t config_size = 0;
    uint64_t video_generation = atomic_load(&app->video_generation);

    if (!ns_recv_all(app->video_fd, codec_data, sizeof(codec_data))) {
        goto cleanup;
    }
    uint32_t wire_codec = ns_read32be(codec_data);
    if (wire_codec != app->expected_codec) {
        fprintf(stderr, "Video codec does not match authenticated binding\n");
        goto cleanup;
    }
    enum AVCodecID codec_id = ns_avcodec_id(wire_codec);
    const AVCodec *codec = avcodec_find_decoder(codec_id);
    if (!codec) {
        fprintf(stderr, "Packaged FFmpeg has no decoder for requested codec\n");
        goto cleanup;
    }
    if (!ns_recv_all(app->video_fd, header, sizeof(header)) || !(header[0] & 0x80)) {
        fprintf(stderr, "Missing initial scrcpy session header\n");
        goto cleanup;
    }
    uint32_t session_width = ns_read32be(header + 4);
    uint32_t session_height = ns_read32be(header + 8);
    if (!session_width || !session_height
            || session_width > NS_MAX_VIDEO_DIMENSION
            || session_height > NS_MAX_VIDEO_DIMENSION
            || (uint64_t) session_width * session_height > NS_MAX_VIDEO_PIXELS) {
        fprintf(stderr, "Invalid initial video dimensions\n");
        goto cleanup;
    }

    codec_context = avcodec_alloc_context3(codec);
    packet = av_packet_alloc();
    decoded = av_frame_alloc();
    if (!codec_context || !packet || !decoded) {
        goto cleanup;
    }
    /* The authenticated source is authorized to send video, but the coded
     * bitstream is still untrusted parser input.  These limits must be in
     * place before avcodec_open2()/SPS parsing can allocate frame storage. */
    av_max_alloc(NS_MAX_FFMPEG_SINGLE_ALLOCATION);
    codec_context->max_pixels = NS_MAX_VIDEO_PIXELS;
    codec_context->thread_count = 1;
    codec_context->thread_type = FF_THREAD_SLICE;
    codec_context->flags |= AV_CODEC_FLAG_LOW_DELAY;
    codec_context->width = (int) session_width;
    codec_context->height = (int) session_height;
    if (avcodec_open2(codec_context, codec, NULL) < 0) {
        fprintf(stderr, "Could not open FFmpeg decoder\n");
        goto cleanup;
    }

    while (atomic_load(&app->running)
            && ns_recv_all(app->video_fd, header, sizeof(header))) {
        if (header[0] & 0x80) {
            uint32_t next_width = ns_read32be(header + 4);
            uint32_t next_height = ns_read32be(header + 8);
            if (!next_width || !next_height
                    || next_width > NS_MAX_VIDEO_DIMENSION
                    || next_height > NS_MAX_VIDEO_DIMENSION
                    || (uint64_t) next_width * next_height
                        > NS_MAX_VIDEO_PIXELS) {
                break;
            }
            if (next_width != session_width || next_height != session_height) {
                session_width = next_width;
                session_height = next_height;
                ++video_generation;
                atomic_store(&app->video_generation, video_generation);
            }
            continue;
        }

        uint64_t pts_flags = ns_read64be(header);
        uint32_t packet_size = ns_read32be(header + 8);
        if (!packet_size || packet_size > NS_MAX_PACKET_SIZE
                || av_new_packet(packet, (int) packet_size) < 0) {
            break;
        }
        if (!ns_recv_all(app->video_fd, packet->data, packet_size)) {
            av_packet_unref(packet);
            break;
        }
        bool is_config = pts_flags & NS_PACKET_FLAG_CONFIG;
        packet->pts = is_config ? AV_NOPTS_VALUE
                                : (int64_t) (pts_flags & NS_PACKET_PTS_MASK);
        packet->dts = packet->pts;
        if (pts_flags & NS_PACKET_FLAG_KEY_FRAME) {
            packet->flags |= AV_PKT_FLAG_KEY;
        }

        bool h26x = wire_codec == NS_CODEC_H264 || wire_codec == NS_CODEC_H265;
        enum ns_decoder_packet_action packet_action =
            ns_prepare_decoder_packet(h26x, is_config, packet, &config,
                                      &config_size);
        if (packet_action != NS_DECODER_PACKET_SEND) {
            av_packet_unref(packet);
            if (packet_action == NS_DECODER_PACKET_ERROR) break;
            continue;
        }

        int sent = avcodec_send_packet(codec_context, packet);
        av_packet_unref(packet);
        if (sent < 0) {
            char error_text[AV_ERROR_MAX_STRING_SIZE];
            av_strerror(sent, error_text, sizeof(error_text));
            fprintf(stderr, "Could not submit video packet to FFmpeg: %s\n",
                    error_text);
            break;
        }
        if (!ns_decode_available(app, codec_context, decoded, &scaler,
                                 video_generation)) {
            break;
        }
    }

cleanup:
    free(config);
    sws_freeContext(scaler);
    av_frame_free(&decoded);
    av_packet_free(&packet);
    avcodec_free_context(&codec_context);
    if (atomic_load(&app->running)) {
        ns_report_worker_failure(app, 1);
    }
    return NULL;
}

static void *
ns_control_main(void *userdata) {
    struct ns_app *app = userdata;
    while (atomic_load(&app->running)) {
        uint8_t type;
        if (!ns_recv_all(app->control_fd, &type, 1)) {
            break;
        }
        if (!app->allow_clipboard) {
            fprintf(stderr,
                    "Rejected scrcpy device message while clipboard is disabled\n");
            break;
        }
        if (type == 0) {
            uint8_t length_data[4];
            if (!ns_recv_all(app->control_fd, length_data, sizeof(length_data))) {
                break;
            }
            uint32_t length = ns_read32be(length_data);
            size_t effective_limit = app->max_clipboard_bytes;
            if (effective_limit > NS_MAX_DEVICE_CLIPBOARD_WIRE) {
                effective_limit = NS_MAX_DEVICE_CLIPBOARD_WIRE;
            }
            if (length > effective_limit) {
                fprintf(stderr, "Remote clipboard exceeds configured limit\n");
                break;
            }
            char *text = malloc((size_t) length + 1);
            if (!text || !ns_recv_all(app->control_fd, text, length)) {
                free(text);
                break;
            }
            text[length] = '\0';
            if (!ns_utf8_valid((const uint8_t *) text, length)) {
                fprintf(stderr, "Remote clipboard is not valid UTF-8\n");
                free(text);
                break;
            }
            if (!ns_queue_remote_clipboard(app, text)) {
                break;
            }
        } else if (type == 1) {
            uint8_t sequence[8];
            if (!ns_recv_all(app->control_fd, sequence, sizeof(sequence))) {
                break;
            }
            atomic_store(&app->acknowledged_clipboard_sequence,
                         ns_read64be(sequence));
        } else {
            fprintf(stderr, "Rejected unsupported scrcpy device message %u\n", type);
            break;
        }
    }
    if (atomic_load(&app->running)) {
        ns_report_worker_failure(app, 2);
    }
    return NULL;
}

static uint32_t
ns_android_meta(uint32_t modifiers) {
    uint32_t meta = 0;
    if (modifiers & SDL_KMOD_SHIFT) meta |= 0x00000001;
    if (modifiers & SDL_KMOD_ALT) meta |= 0x00000002;
    if (modifiers & SDL_KMOD_CTRL) meta |= 0x00001000;
    if (modifiers & SDL_KMOD_GUI) meta |= 0x00010000;
    if (modifiers & SDL_KMOD_LSHIFT) meta |= 0x00000040;
    if (modifiers & SDL_KMOD_RSHIFT) meta |= 0x00000080;
    if (modifiers & SDL_KMOD_LALT) meta |= 0x00000010;
    if (modifiers & SDL_KMOD_RALT) meta |= 0x00000020;
    if (modifiers & SDL_KMOD_LCTRL) meta |= 0x00002000;
    if (modifiers & SDL_KMOD_RCTRL) meta |= 0x00004000;
    if (modifiers & SDL_KMOD_LGUI) meta |= 0x00020000;
    if (modifiers & SDL_KMOD_RGUI) meta |= 0x00040000;
    return meta;
}

static int
ns_android_keycode(SDL_Keycode key, uint32_t modifiers) {
    uint32_t shortcut_modifiers = modifiers & (SDL_KMOD_CTRL | SDL_KMOD_GUI);
    if (shortcut_modifiers) {
        if (key == NS_SDLK_A + ('h' - 'a')) return 3;   /* Android HOME. */
        if (key == NS_SDLK_A + ('s' - 'a')) return 187; /* Android APP_SWITCH. */
    }
    switch (key) {
        case SDLK_BACKSPACE: return 67;
        case SDLK_RETURN: return 66;
        case SDLK_ESCAPE: return 4;
        case SDLK_F1: return 3;
        case SDLK_F2: return 187;
        case SDLK_TAB: return 61;
        case SDLK_DELETE: return 112;
        case SDLK_HOME: return 122;
        case SDLK_END: return 123;
        case SDLK_PAGEUP: return 92;
        case SDLK_PAGEDOWN: return 93;
        case SDLK_LEFT: return 21;
        case SDLK_RIGHT: return 22;
        case SDLK_UP: return 19;
        case SDLK_DOWN: return 20;
        case SDLK_F12: return NS_KEYCODE_TOGGLE_POWER;
        default: break;
    }
    if (modifiers & (SDL_KMOD_CTRL | SDL_KMOD_GUI | SDL_KMOD_ALT)) {
        if (key >= NS_SDLK_A && key <= NS_SDLK_Z) {
            return 29 + (int) (key - NS_SDLK_A);
        }
        if (key >= SDLK_0 && key <= SDLK_9) return 7 + (int) (key - SDLK_0);
    }
    return -1;
}

static bool
ns_send_key(struct ns_app *app, bool down, int keycode, bool repeat,
            uint32_t modifiers) {
    if (!app->allow_control) return false;
    if (keycode == NS_KEYCODE_BACK_OR_SCREEN_ON) {
        uint8_t message[] = {
            NS_CONTROL_BACK_OR_SCREEN_ON,
            down ? NS_ACTION_DOWN : NS_ACTION_UP,
        };
        return ns_control_enqueue_copy(app, message, sizeof(message), true,
                                       NS_COALESCE_NONE, 0);
    }
    uint8_t message[14] = { NS_CONTROL_INJECT_KEYCODE,
                            down ? NS_ACTION_DOWN : NS_ACTION_UP };
    ns_write32be(message + 2, (uint32_t) keycode);
    ns_write32be(message + 6, repeat ? 1 : 0);
    ns_write32be(message + 10, ns_android_meta(modifiers));
    return ns_control_enqueue_copy(app, message, sizeof(message), true,
                                   NS_COALESCE_NONE, 0);
}

static bool
ns_send_power_toggle(struct ns_app *app) {
    if (!app->allow_control) return false;
    const uint8_t message[] = { NS_CONTROL_TOGGLE_POWER };
    return ns_control_enqueue_copy(app, message, sizeof(message), true,
                                   NS_COALESCE_NONE, 0);
}

static bool
ns_activate_function_button(struct ns_app *app,
                            enum ns_function_button button) {
    int keycode;
    switch (button) {
        case NS_FUNCTION_BACK: keycode = 4; break;
        case NS_FUNCTION_HOME: keycode = 3; break;
        case NS_FUNCTION_RECENTS: keycode = 187; break;
        case NS_FUNCTION_POWER: return ns_send_power_toggle(app);
        case NS_FUNCTION_NONE: return false;
    }
    bool down = ns_send_key(app, true, keycode, false, 0);
    bool up = ns_send_key(app, false, keycode, false, 0);
    return down && up;
}

static bool
ns_send_text(struct ns_app *app, const char *text) {
    if (!app->allow_control) return false;
    size_t length = strlen(text);
    if (!length || length > 300 || !ns_utf8_valid((const uint8_t *) text, length)) {
        return false;
    }
    uint8_t *message = malloc(length + 5);
    if (!message) return false;
    message[0] = NS_CONTROL_INJECT_TEXT;
    ns_write32be(message + 1, (uint32_t) length);
    memcpy(message + 5, text, length);
    return ns_control_enqueue_owned(app, message, length + 5, true,
                                    NS_COALESCE_NONE, 0, 0);
}

static uint32_t
ns_android_buttons(uint32_t buttons) {
    uint32_t result = 0;
    if (buttons & SDL_BUTTON_LMASK) result |= 1;
    if (buttons & SDL_BUTTON_RMASK) result |= 2;
    if (buttons & SDL_BUTTON_MMASK) result |= 4;
    if (buttons & SDL_BUTTON_X1MASK) result |= 8;
    if (buttons & SDL_BUTTON_X2MASK) result |= 16;
    return result;
}

static uint32_t
ns_android_button(uint8_t button) {
    switch (button) {
        case SDL_BUTTON_LEFT: return 1;
        case SDL_BUTTON_RIGHT: return 2;
        case SDL_BUTTON_MIDDLE: return 4;
        case SDL_BUTTON_X1: return 8;
        case SDL_BUTTON_X2: return 16;
        default: return 0;
    }
}

static bool
ns_send_touch(struct ns_app *app, uint8_t action, uint64_t pointer_id,
              uint16_t x, uint16_t y, float pressure,
              uint32_t action_button, uint32_t buttons) {
    if (!app->allow_control || !app->source_width || !app->source_height) {
        return false;
    }
    uint8_t message[32] = { NS_CONTROL_INJECT_TOUCH, action };
    ns_write64be(message + 2, pointer_id);
    ns_write32be(message + 10, x);
    ns_write32be(message + 14, y);
    ns_write16be(message + 18, app->source_width);
    ns_write16be(message + 20, app->source_height);
    if (pressure < 0) pressure = 0;
    if (pressure > 1) pressure = 1;
    uint32_t fixed_pressure = (uint32_t) (pressure * 65536);
    if (fixed_pressure >= UINT16_MAX) fixed_pressure = UINT16_MAX;
    ns_write16be(message + 22, (uint16_t) fixed_pressure);
    ns_write32be(message + 24, action_button);
    ns_write32be(message + 28, buttons);
    bool essential = action != NS_ACTION_MOVE;
    if (action == NS_ACTION_CANCEL) {
        // Android CANCEL is global: no queued move for any pointer may follow it.
        ns_control_drop_all_coalesced(app, NS_COALESCE_TOUCH_MOVE);
    } else if (essential) {
        // DOWN starts a fresh pointer lifecycle; UP/CANCEL already carry the
        // final position, so an older queued move must not trail the terminal
        // transition after priority insertion.
        ns_control_drop_coalesced(app, NS_COALESCE_TOUCH_MOVE, pointer_id);
    }
    return ns_control_enqueue_copy(
        app, message, sizeof(message), essential,
        essential ? NS_COALESCE_NONE : NS_COALESCE_TOUCH_MOVE,
        pointer_id);
}

static bool
ns_send_global_cancel(struct ns_app *app) {
    if (!ns_input_state_active(&app->input_state)) return true;
    uint64_t pointer_id = app->input_state.mouse_active
        ? NS_POINTER_ID_MOUSE : app->input_state.touches[0];
    return ns_send_touch(app, NS_ACTION_CANCEL, pointer_id, 0, 0, 0, 0, 0);
}

static int16_t
ns_scroll_fixed(float scroll) {
    if (scroll < -16) scroll = -16;
    if (scroll > 16) scroll = 16;
    float normalized = scroll / 16;
    int32_t fixed = (int32_t) (normalized * 32768);
    if (fixed >= INT16_MAX) fixed = INT16_MAX;
    return (int16_t) fixed;
}

static bool
ns_send_scroll(struct ns_app *app, uint16_t x, uint16_t y,
               float horizontal, float vertical, uint32_t buttons) {
    if (!app->allow_control) return false;
    uint8_t message[21] = { NS_CONTROL_INJECT_SCROLL };
    ns_write32be(message + 1, x);
    ns_write32be(message + 5, y);
    ns_write16be(message + 9, app->source_width);
    ns_write16be(message + 11, app->source_height);
    ns_write16be(message + 13, (uint16_t) ns_scroll_fixed(horizontal));
    ns_write16be(message + 15, (uint16_t) ns_scroll_fixed(vertical));
    ns_write32be(message + 17, buttons);
    return ns_control_enqueue_copy(app, message, sizeof(message), false,
                                   NS_COALESCE_SCROLL, 0);
}

static bool
ns_send_clipboard(struct ns_app *app, const char *text, uint64_t *sequence_out) {
    if (!app->allow_clipboard) return false;
    size_t length = strlen(text);
    size_t limit = app->max_clipboard_bytes;
    if (limit > NS_MAX_CONTROL_CLIPBOARD_WIRE) {
        limit = NS_MAX_CONTROL_CLIPBOARD_WIRE;
    }
    if (length > limit || !ns_utf8_valid((const uint8_t *) text, length)) {
        return false;
    }
    uint8_t *message = malloc(length + 14);
    if (!message) return false;
    message[0] = NS_CONTROL_SET_CLIPBOARD;
    uint64_t sequence = ++app->clipboard_sequence;
    if (!sequence) sequence = ++app->clipboard_sequence;
    ns_write64be(message + 1, sequence);
    message[9] = 0;
    ns_write32be(message + 10, (uint32_t) length);
    memcpy(message + 14, text, length);
    bool result = ns_control_enqueue_owned(
        app, message, length + 14, false, NS_COALESCE_CLIPBOARD, 0,
        sequence);
    if (result && sequence_out) *sequence_out = sequence;
    return result;
}

static SDL_FRect
ns_destination_rect_in_bounds(SDL_FRect bounds, uint16_t source_width,
                              uint16_t source_height) {
    SDL_FRect result = bounds;
    if (!source_width || !source_height || bounds.w <= 0 || bounds.h <= 0) {
        return result;
    }
    float source_aspect = (float) source_width / source_height;
    float output_aspect = bounds.w / bounds.h;
    if (output_aspect > source_aspect) {
        result.w = bounds.h * source_aspect;
        result.x = bounds.x + (bounds.w - result.w) / 2;
    } else {
        result.h = bounds.w / source_aspect;
        result.y = bounds.y + (bounds.h - result.h) / 2;
    }
    return result;
}

static SDL_FRect
ns_destination_rect(int output_width, int output_height,
                    uint16_t source_width, uint16_t source_height) {
    SDL_FRect bounds = { 0, 0, (float) output_width, (float) output_height };
    return ns_destination_rect_in_bounds(bounds, source_width, source_height);
}

static struct ns_view_layout
ns_view_layout(float width, float height, float function_bar_height,
               uint16_t source_width, uint16_t source_height) {
    struct ns_view_layout result;
    memset(&result, 0, sizeof(result));
    if (width < 0) width = 0;
    if (height < 0) height = 0;
    if (function_bar_height < 0) function_bar_height = 0;
    if (function_bar_height > height) function_bar_height = height;

    result.video_bounds = (SDL_FRect) {
        0, 0, width, height - function_bar_height,
    };
    result.video_destination = ns_destination_rect_in_bounds(
        result.video_bounds, source_width, source_height);
    result.function_bar = (SDL_FRect) {
        0, height - function_bar_height, width, function_bar_height,
    };

    float horizontal_padding = fminf(12.0f, width / 32.0f);
    float gap = fminf(10.0f, width / 40.0f);
    float vertical_padding = fminf(8.0f, function_bar_height / 8.0f);
    float button_width = (width - 2 * horizontal_padding
                          - (NS_FUNCTION_BUTTON_COUNT - 1) * gap)
                         / NS_FUNCTION_BUTTON_COUNT;
    if (button_width < 0) button_width = 0;
    float button_height = function_bar_height - 2 * vertical_padding;
    if (button_height < 0) button_height = 0;
    for (int i = 0; i < NS_FUNCTION_BUTTON_COUNT; ++i) {
        result.function_buttons[i] = (SDL_FRect) {
            horizontal_padding + i * (button_width + gap),
            result.function_bar.y + vertical_padding,
            button_width,
            button_height,
        };
    }
    return result;
}

static bool
ns_point_in_rect(const SDL_FRect *rect, float x, float y) {
    return rect->w > 0 && rect->h > 0
        && x >= rect->x && y >= rect->y
        && x < rect->x + rect->w && y < rect->y + rect->h;
}

static enum ns_function_button
ns_function_button_at(const struct ns_view_layout *layout, float x, float y) {
    if (!ns_point_in_rect(&layout->function_bar, x, y)) {
        return NS_FUNCTION_NONE;
    }
    for (int i = 0; i < NS_FUNCTION_BUTTON_COUNT; ++i) {
        if (ns_point_in_rect(&layout->function_buttons[i], x, y)) {
            return (enum ns_function_button) i;
        }
    }
    return NS_FUNCTION_NONE;
}

static bool
ns_view_to_device(int width, int height, uint16_t source_width,
                  uint16_t source_height, float window_x, float window_y,
                  bool clamp, uint16_t *device_x, uint16_t *device_y) {
    if (!source_width || !source_height || width <= 0 || height <= 0) {
        return false;
    }
    struct ns_view_layout layout = ns_view_layout(
        width, height, NS_FUNCTION_BAR_HEIGHT, source_width, source_height);
    SDL_FRect destination = layout.video_destination;
    if (!destination.w || !destination.h) {
        return false;
    }
    bool outside = window_x < destination.x || window_y < destination.y
                || window_x >= destination.x + destination.w
                || window_y >= destination.y + destination.h;
    if (outside && !clamp) return false;
    if (window_x < destination.x) window_x = destination.x;
    if (window_y < destination.y) window_y = destination.y;
    if (window_x >= destination.x + destination.w) {
        window_x = destination.x + destination.w - 1;
    }
    if (window_y >= destination.y + destination.h) {
        window_y = destination.y + destination.h - 1;
    }
    float x = (window_x - destination.x) * source_width / destination.w;
    float y = (window_y - destination.y) * source_height / destination.h;
    if (x < 0) x = 0;
    if (y < 0) y = 0;
    if (x >= source_width) x = source_width - 1;
    if (y >= source_height) y = source_height - 1;
    *device_x = (uint16_t) x;
    *device_y = (uint16_t) y;
    return true;
}

static bool
ns_window_to_device_internal(SDL_Window *window, struct ns_app *app,
                             float window_x, float window_y, bool clamp,
                             uint16_t *device_x, uint16_t *device_y) {
    int width = 0;
    int height = 0;
    SDL_GetWindowSize(window, &width, &height);
    return ns_view_to_device(width, height, app->source_width,
                             app->source_height, window_x, window_y, clamp,
                             device_x, device_y);
}

static bool
ns_window_to_device(SDL_Window *window, struct ns_app *app, float window_x,
                    float window_y, uint16_t *device_x, uint16_t *device_y) {
    return ns_window_to_device_internal(window, app, window_x, window_y, false,
                                        device_x, device_y);
}

static bool
ns_window_to_device_clamped(SDL_Window *window, struct ns_app *app,
                            float window_x, float window_y,
                            uint16_t *device_x, uint16_t *device_y) {
    return ns_window_to_device_internal(window, app, window_x, window_y, true,
                                        device_x, device_y);
}

static struct ns_view_layout
ns_window_layout(SDL_Window *window, struct ns_app *app) {
    int width = 0;
    int height = 0;
    SDL_GetWindowSize(window, &width, &height);
    return ns_view_layout(width, height, NS_FUNCTION_BAR_HEIGHT,
                          app->source_width, app->source_height);
}

static void
ns_render_line(SDL_Renderer *renderer, float x1, float y1,
               float x2, float y2) {
#if NS_SDL3
    SDL_RenderLine(renderer, x1, y1, x2, y2);
#else
    SDL_RenderDrawLine(renderer, (int) lroundf(x1), (int) lroundf(y1),
                      (int) lroundf(x2), (int) lroundf(y2));
#endif
}

static void
ns_render_thick_line(SDL_Renderer *renderer, float x1, float y1,
                     float x2, float y2, float thickness) {
    float dx = x2 - x1;
    float dy = y2 - y1;
    float length = sqrtf(dx * dx + dy * dy);
    if (length <= 0) return;
    int lines = (int) ceilf(fmaxf(1.0f, thickness));
    float normal_x = -dy / length;
    float normal_y = dx / length;
    for (int i = 0; i < lines; ++i) {
        float offset = i - (lines - 1) / 2.0f;
        ns_render_line(renderer,
                       x1 + normal_x * offset, y1 + normal_y * offset,
                       x2 + normal_x * offset, y2 + normal_y * offset);
    }
}

static void
ns_render_fill_rect(SDL_Renderer *renderer, const SDL_FRect *rect) {
#if NS_SDL3
    SDL_RenderFillRect(renderer, rect);
#else
    SDL_Rect integer_rect = {
        (int) floorf(rect->x), (int) floorf(rect->y),
        (int) ceilf(rect->w), (int) ceilf(rect->h),
    };
    SDL_RenderFillRect(renderer, &integer_rect);
#endif
}

static void
ns_render_rect_outline(SDL_Renderer *renderer, const SDL_FRect *rect,
                       float thickness) {
    float right = rect->x + rect->w;
    float bottom = rect->y + rect->h;
    ns_render_thick_line(renderer, rect->x, rect->y, right, rect->y,
                         thickness);
    ns_render_thick_line(renderer, right, rect->y, right, bottom, thickness);
    ns_render_thick_line(renderer, right, bottom, rect->x, bottom, thickness);
    ns_render_thick_line(renderer, rect->x, bottom, rect->x, rect->y,
                         thickness);
}

static void
ns_render_arc(SDL_Renderer *renderer, float center_x, float center_y,
              float radius, float start, float end, int segments,
              float thickness) {
    float previous_x = center_x + sinf(start) * radius;
    float previous_y = center_y - cosf(start) * radius;
    for (int i = 1; i <= segments; ++i) {
        float angle = start + (end - start) * i / segments;
        float x = center_x + sinf(angle) * radius;
        float y = center_y - cosf(angle) * radius;
        ns_render_thick_line(renderer, previous_x, previous_y, x, y,
                             thickness);
        previous_x = x;
        previous_y = y;
    }
}

static void
ns_render_function_icon(SDL_Renderer *renderer,
                        enum ns_function_button button,
                        const SDL_FRect *bounds, float thickness) {
    float center_x = bounds->x + bounds->w / 2;
    float center_y = bounds->y + bounds->h / 2;
    float radius = fminf(bounds->w, bounds->h) * 0.22f;
    switch (button) {
        case NS_FUNCTION_BACK:
            ns_render_thick_line(renderer, center_x - radius, center_y,
                                 center_x + radius * 0.65f,
                                 center_y - radius, thickness);
            ns_render_thick_line(renderer, center_x + radius * 0.65f,
                                 center_y - radius,
                                 center_x + radius * 0.65f,
                                 center_y + radius, thickness);
            ns_render_thick_line(renderer, center_x + radius * 0.65f,
                                 center_y + radius,
                                 center_x - radius, center_y, thickness);
            break;
        case NS_FUNCTION_HOME:
            ns_render_arc(renderer, center_x, center_y, radius, 0,
                          6.28318530718f, 28, thickness);
            break;
        case NS_FUNCTION_RECENTS: {
            SDL_FRect square = {
                center_x - radius, center_y - radius,
                radius * 2, radius * 2,
            };
            ns_render_rect_outline(renderer, &square, thickness);
            break;
        }
        case NS_FUNCTION_POWER:
            ns_render_arc(renderer, center_x, center_y + radius * 0.12f,
                          radius, 0.72f, 6.28318530718f - 0.72f,
                          24, thickness);
            ns_render_thick_line(renderer, center_x,
                                 center_y - radius * 1.15f,
                                 center_x, center_y + radius * 0.12f,
                                 thickness);
            break;
        case NS_FUNCTION_NONE:
            break;
    }
}

static void
ns_render_viewer(SDL_Window *window, SDL_Renderer *renderer,
                 SDL_Texture *texture, struct ns_app *app) {
    int output_width = 0;
    int output_height = 0;
#if NS_SDL3
    SDL_GetRenderOutputSize(renderer, &output_width, &output_height);
#else
    SDL_GetRendererOutputSize(renderer, &output_width, &output_height);
#endif
    if (output_width <= 0 || output_height <= 0) return;

    int window_width = 0;
    int window_height = 0;
    SDL_GetWindowSize(window, &window_width, &window_height);
    float scale_y = window_height > 0
                  ? (float) output_height / window_height : 1.0f;
    struct ns_view_layout layout = ns_view_layout(
        output_width, output_height, NS_FUNCTION_BAR_HEIGHT * scale_y,
        app->source_width, app->source_height);

    SDL_SetRenderDrawColor(renderer, 0, 0, 0, 255);
    SDL_RenderClear(renderer);
    if (texture && app->source_width && app->source_height) {
#if NS_SDL3
        SDL_RenderTexture(renderer, texture, NULL, &layout.video_destination);
#else
        SDL_Rect destination = {
            (int) lroundf(layout.video_destination.x),
            (int) lroundf(layout.video_destination.y),
            (int) lroundf(layout.video_destination.w),
            (int) lroundf(layout.video_destination.h),
        };
        SDL_RenderCopy(renderer, texture, NULL, &destination);
#endif
    }

    SDL_SetRenderDrawColor(renderer, 22, 24, 30, 255);
    ns_render_fill_rect(renderer, &layout.function_bar);
    SDL_SetRenderDrawColor(renderer, 72, 78, 92, 255);
    ns_render_line(renderer, 0, layout.function_bar.y,
                   (float) output_width, layout.function_bar.y);

    for (int i = 0; i < NS_FUNCTION_BUTTON_COUNT; ++i) {
        bool pressed = app->pressed_function_button
                    == (enum ns_function_button) i;
        if (!app->allow_control) {
            SDL_SetRenderDrawColor(renderer, 29, 31, 37, 255);
        } else if (pressed) {
            SDL_SetRenderDrawColor(renderer, 67, 76, 94, 255);
        } else {
            SDL_SetRenderDrawColor(renderer, 37, 41, 50, 255);
        }
        ns_render_fill_rect(renderer, &layout.function_buttons[i]);
        SDL_SetRenderDrawColor(renderer,
                               app->allow_control ? 91 : 54,
                               app->allow_control ? 99 : 58,
                               app->allow_control ? 116 : 67, 255);
        ns_render_rect_outline(renderer, &layout.function_buttons[i],
                               fmaxf(1.0f, layout.function_bar.h / 64.0f));
        SDL_SetRenderDrawColor(renderer,
                               app->allow_control ? 231 : 112,
                               app->allow_control ? 234 : 116,
                               app->allow_control ? 240 : 126, 255);
        ns_render_function_icon(
            renderer, (enum ns_function_button) i,
            &layout.function_buttons[i],
            fmaxf(2.0f, layout.function_bar.h / 30.0f));
    }
    SDL_RenderPresent(renderer);
}

static void
ns_refresh_clipboard_ack(struct ns_app *app, int64_t now_ms) {
    uint64_t acknowledged = atomic_load(&app->acknowledged_clipboard_sequence);
    if (app->pending_local_clipboard_sequence
            && acknowledged == app->pending_local_clipboard_sequence) {
        ns_clear_pending_local_clipboard(app);
    } else if (app->pending_local_clipboard
            && now_ms >= app->pending_local_clipboard_deadline_ms) {
        // A bounded sender or broken connection may lose an ACK. Never suppress an
        // identical future local edit forever; expiry makes loss fail safe.
        ns_clear_pending_local_clipboard(app);
    }
}

static void
ns_clear_remote_clipboard_marker(struct ns_app *app) {
    free(app->remote_clipboard_reflection);
    app->remote_clipboard_reflection = NULL;
    app->pending_remote_clipboard_reflections = 0;
    app->remote_clipboard_reflection_deadline_ms = 0;
}

static bool
ns_suppress_reflected_remote_clipboard(struct ns_app *app,
                                       const char *clipboard,
                                       int64_t now_ms) {
    if (!app->remote_clipboard_reflection) return false;
    if (now_ms >= app->remote_clipboard_reflection_deadline_ms) {
        ns_clear_remote_clipboard_marker(app);
        return false;
    }
    if (!strcmp(clipboard, app->remote_clipboard_reflection)) {
        if (app->pending_remote_clipboard_reflections) {
            --app->pending_remote_clipboard_reflections;
        }
        if (!app->pending_remote_clipboard_reflections) {
            ns_clear_remote_clipboard_marker(app);
        }
        return true;
    }
    // Clipboard notifications may be delayed or reordered. An unrelated
    // local edit must not consume the marker for the remote write.
    return false;
}

static void
ns_handle_local_clipboard(struct ns_app *app) {
    if (!app->allow_clipboard) return;
    int64_t now_ms = ns_monotonic_millis();
    ns_refresh_clipboard_ack(app, now_ms);
    char *clipboard = SDL_GetClipboardText();
    if (!clipboard) return;
    size_t length = strlen(clipboard);
    size_t limit = app->max_clipboard_bytes;
    if (limit > NS_MAX_CONTROL_CLIPBOARD_WIRE) {
        limit = NS_MAX_CONTROL_CLIPBOARD_WIRE;
    }

    if (ns_suppress_reflected_remote_clipboard(
            app, clipboard, now_ms)) {
        SDL_free(clipboard);
        return;
    }

    if (app->pending_local_clipboard
            && !strcmp(clipboard, app->pending_local_clipboard)) {
        SDL_free(clipboard);
        return;
    }
    ns_clear_pending_local_clipboard(app);

    uint64_t sequence = 0;
    if (length <= limit && ns_utf8_valid((const uint8_t *) clipboard, length)
            && ns_send_clipboard(app, clipboard, &sequence)) {
        char *pending = strdup(clipboard);
        if (pending) {
            app->pending_local_clipboard = pending;
            app->pending_local_clipboard_sequence = sequence;
            app->pending_local_clipboard_deadline_ms =
                now_ms + NS_LOCAL_CLIPBOARD_ACK_TIMEOUT_MS;
        }
    }
    SDL_free(clipboard);
}

static bool
ns_ffmpeg_lgpl_compatible(const char *configuration) {
    return configuration && !strstr(configuration, "--enable-gpl")
           && !strstr(configuration, "--enable-nonfree");
}

static bool
ns_sdl_init(void) {
#if NS_SDL3
    return SDL_Init(SDL_INIT_VIDEO | SDL_INIT_EVENTS);
#else
    return SDL_Init(SDL_INIT_VIDEO | SDL_INIT_EVENTS) == 0;
#endif
}

static bool
ns_sdl_set_clipboard(const char *text) {
#if NS_SDL3
    return SDL_SetClipboardText(text);
#else
    return SDL_SetClipboardText(text) == 0;
#endif
}

static void
ns_handle_remote_clipboard(struct ns_app *app, const char *text) {
    if (!app->allow_clipboard) return;
    ns_refresh_clipboard_ack(app, ns_monotonic_millis());

    // A reflected local write confirms its origin even if the acknowledgement
    // event has not reached the SDL thread yet. A different remote value wins.
    ns_clear_pending_local_clipboard(app);

    char *current = SDL_GetClipboardText();
    bool unchanged = current && !strcmp(current, text);
    SDL_free(current);
    int64_t now_ms = ns_monotonic_millis();
    if (unchanged) {
        if (!app->remote_clipboard_reflection
                || strcmp(app->remote_clipboard_reflection, text)
                || now_ms >= app->remote_clipboard_reflection_deadline_ms) {
            ns_clear_remote_clipboard_marker(app);
        }
        return;
    }

    char *marker = strdup(text);
    if (!marker) {
        fprintf(stderr, "Could not allocate clipboard loop marker\n");
        return;
    }
    unsigned int pending_reflections =
        now_ms < app->remote_clipboard_reflection_deadline_ms
        ? app->pending_remote_clipboard_reflections : 0;
    ns_clear_remote_clipboard_marker(app);
    app->remote_clipboard_reflection = marker;
    app->pending_remote_clipboard_reflections =
        pending_reflections < NS_MAX_PENDING_CLIPBOARD_REFLECTIONS
        ? pending_reflections + 1 : NS_MAX_PENDING_CLIPBOARD_REFLECTIONS;
    app->remote_clipboard_reflection_deadline_ms =
        now_ms + NS_CLIPBOARD_REFLECTION_TIMEOUT_MS;
    if (!ns_sdl_set_clipboard(text)) {
        fprintf(stderr, "Could not update desktop clipboard: %s\n", SDL_GetError());
        ns_clear_remote_clipboard_marker(app);
    }
}

static int
ns_run_viewer(const struct ns_options *options) {
    const char *ffmpeg_configuration = avcodec_configuration();
    if (!ns_ffmpeg_lgpl_compatible(ffmpeg_configuration)) {
        fprintf(stderr,
                "Warning: the developer-linked FFmpeg enables GPL/nonfree components; "
                "this local helper must not be redistributed. NotiSync release packaging "
                "still requires and validates an LGPL-compatible runtime.\n");
    }

    struct ns_app app;
    memset(&app, 0, sizeof(app));
    app.video_fd = -1;
    app.control_fd = -1;
    app.expected_codec = options->codec;
    app.max_clipboard_bytes = options->max_clipboard_bytes;
    app.allow_control = options->allow_control;
    app.allow_clipboard = options->allow_clipboard;
    atomic_init(&app.running, true);
    atomic_init(&app.worker_failure, 0);
    atomic_init(&app.acknowledged_clipboard_sequence, 0);
    atomic_init(&app.video_generation, 1);
    app.applied_video_generation = 0;
    app.pointer_input_ready = false;
    app.pressed_function_button = NS_FUNCTION_NONE;
    pthread_mutex_init(&app.frame_mutex, NULL);
    pthread_mutex_init(&app.control_queue_mutex, NULL);
    pthread_cond_init(&app.control_queue_cond, NULL);
    pthread_mutex_init(&app.clipboard_mutex, NULL);

    int exit_code = NS_EXIT_RUNTIME;
    SDL_Window *window = NULL;
    SDL_Renderer *renderer = NULL;
    SDL_Texture *texture = NULL;

    uint8_t helper_challenge[NS_HELPER_CHALLENGE_BYTES];
    if (!ns_read_all_fd(STDIN_FILENO, helper_challenge,
                        sizeof(helper_challenge))) {
        fprintf(stderr, "Could not read private helper challenge\n");
        goto cleanup;
    }

    if (!ns_sdl_init()) {
        fprintf(stderr, "SDL initialization failed: %s\n", SDL_GetError());
        goto cleanup;
    }
    app.frame_event = SDL_RegisterEvents(1);
    app.remote_clipboard_event = SDL_RegisterEvents(1);
    app.disconnected_event = SDL_RegisterEvents(1);
    if (app.frame_event == (uint32_t) -1
            || app.remote_clipboard_event == (uint32_t) -1
            || app.disconnected_event == (uint32_t) -1) {
        fprintf(stderr, "SDL could not reserve internal events\n");
        goto cleanup;
    }

#if NS_SDL3
    window = SDL_CreateWindow(options->title, 720, 960,
                              SDL_WINDOW_RESIZABLE | SDL_WINDOW_HIGH_PIXEL_DENSITY);
#else
    window = SDL_CreateWindow(options->title, SDL_WINDOWPOS_CENTERED,
                              SDL_WINDOWPOS_CENTERED, 720, 960,
                              SDL_WINDOW_RESIZABLE | SDL_WINDOW_HIGH_PIXEL_DENSITY);
#endif
    if (!window) {
        fprintf(stderr, "Could not create viewer window: %s\n", SDL_GetError());
        goto cleanup;
    }
#if NS_SDL3
    renderer = SDL_CreateRenderer(window, NULL);
    if (app.allow_control) SDL_StartTextInput(window);
#else
    renderer = SDL_CreateRenderer(window, -1, SDL_RENDERER_ACCELERATED);
    if (app.allow_control) SDL_StartTextInput();
#endif
    if (!renderer) {
        fprintf(stderr, "Could not create SDL renderer: %s\n", SDL_GetError());
        goto cleanup;
    }
    // The power/navigation controls must be available while the remote display
    // is asleep, before a decoder frame exists, and while a still frame remains.
    ns_render_viewer(window, renderer, NULL, &app);

    app.video_fd = ns_connect_unix(options->video_socket, helper_challenge,
                                   NS_HELPER_VIDEO_CHANNEL);
    if (app.video_fd < 0) {
        perror("Could not connect video Unix socket");
        goto cleanup;
    }
    app.control_fd = ns_connect_unix(options->control_socket, helper_challenge,
                                     NS_HELPER_CONTROL_CHANNEL);
    if (app.control_fd < 0) {
        perror("Could not connect control Unix socket");
        goto cleanup;
    }
    for (size_t i = 0; i < sizeof(helper_challenge); ++i) {
        ((volatile uint8_t *) helper_challenge)[i] = 0;
    }
    if (pthread_create(&app.video_thread, NULL, ns_video_main, &app)) {
        fprintf(stderr, "Could not start decoder thread\n");
        goto cleanup;
    }
    app.video_thread_started = true;
    if (app.allow_control || app.allow_clipboard) {
        if (pthread_create(&app.control_writer_thread, NULL,
                           ns_control_writer_main, &app)) {
            fprintf(stderr, "Could not start control writer thread\n");
            goto cleanup;
        }
        app.control_writer_thread_started = true;
    }
    if (pthread_create(&app.control_thread, NULL, ns_control_main, &app)) {
        fprintf(stderr, "Could not start control thread\n");
        goto cleanup;
    }
    app.control_thread_started = true;

    exit_code = 0;
    bool quit = false;
    while (!quit && atomic_load(&app.running)) {
        ns_reconcile_video_generation(&app);
        int worker_failure = atomic_load(&app.worker_failure);
        if (worker_failure) {
            fprintf(stderr, "%s channel closed\n",
                    worker_failure == 1 ? "Video" : "Control");
            quit = true;
            exit_code = NS_EXIT_RUNTIME;
            continue;
        }
        if (ns_stop_requested) {
            quit = true;
            continue;
        }
        SDL_Event event;
        if (!SDL_WaitEventTimeout(&event, 50)) {
            continue;
        }
        ns_reconcile_video_generation(&app);
        if (event.type == SDL_EVENT_QUIT) {
            quit = true;
#if NS_SDL3
        } else if (event.type == SDL_EVENT_WINDOW_CLOSE_REQUESTED) {
            quit = true;
#endif
        } else if (event.type == app.disconnected_event) {
            fprintf(stderr, "%s channel closed\n",
                    event.user.code == 1 ? "Video" : "Control");
            quit = true;
            exit_code = NS_EXIT_RUNTIME;
        } else if (event.type == app.frame_event) {
            pthread_mutex_lock(&app.frame_mutex);
            struct ns_frame *frame = app.pending_frame;
            app.pending_frame = NULL;
            pthread_mutex_unlock(&app.frame_mutex);
            if (!frame) continue;

            uint64_t expected_generation = atomic_load(&app.video_generation);
            if (frame->video_generation != expected_generation
                    || frame->video_generation != app.applied_video_generation) {
                ns_free_frame(frame);
                continue;
            }

            if (!texture || frame->width != app.source_width
                    || frame->height != app.source_height) {
                SDL_DestroyTexture(texture);
                texture = SDL_CreateTexture(renderer, SDL_PIXELFORMAT_RGBA32,
                                            SDL_TEXTUREACCESS_STREAMING,
                                            frame->width, frame->height);
                if (!texture) {
                    ns_free_frame(frame);
                    exit_code = NS_EXIT_RUNTIME;
                    quit = true;
                    continue;
                }
                app.source_width = (uint16_t) frame->width;
                app.source_height = (uint16_t) frame->height;
                int requested_video_height = 900;
                int requested_width = (int) lround(
                    (double) requested_video_height
                    * frame->width / frame->height);
                if (requested_width > 1200) {
                    requested_width = 1200;
                    requested_video_height = (int) lround(
                        (double) requested_width * frame->height / frame->width);
                }
                SDL_SetWindowSize(
                    window, requested_width,
                    requested_video_height + (int) NS_FUNCTION_BAR_HEIGHT);
            }
            app.pointer_input_ready =
                frame->video_generation == atomic_load(&app.video_generation);
            SDL_UpdateTexture(texture, NULL, frame->rgba, frame->pitch);
            ns_free_frame(frame);
        } else if (app.allow_clipboard
                && event.type == app.remote_clipboard_event) {
            char *text = ns_take_remote_clipboard(&app);
            if (text) {
                ns_handle_remote_clipboard(&app, text);
                free(text);
            }
        } else if (app.allow_clipboard
                && event.type == SDL_EVENT_CLIPBOARD_UPDATE) {
            ns_handle_local_clipboard(&app);
        } else if (app.allow_control
                && (event.type == SDL_EVENT_KEY_DOWN
                    || event.type == SDL_EVENT_KEY_UP)) {
#if NS_SDL3
            SDL_Keycode key = event.key.key;
            uint32_t modifiers = event.key.mod;
            bool repeat = event.key.repeat;
#else
            SDL_Keycode key = event.key.keysym.sym;
            uint32_t modifiers = event.key.keysym.mod;
            bool repeat = event.key.repeat;
#endif
            int keycode = ns_android_keycode(key, modifiers);
            bool down = event.type == SDL_EVENT_KEY_DOWN;
            if (keycode == NS_KEYCODE_TOGGLE_POWER) {
                // Power is a one-byte edge-triggered NotiSync command, not an
                // Android key lifecycle. Ignore key-up and keyboard repeat.
                if (down && !repeat) ns_send_power_toggle(&app);
            } else if (keycode >= 0
                    || keycode == NS_KEYCODE_BACK_OR_SCREEN_ON) {
                ns_send_key(&app, event.type == SDL_EVENT_KEY_DOWN,
                            keycode, repeat, modifiers);
            }
        } else if (app.allow_control && event.type == SDL_EVENT_TEXT_INPUT) {
            ns_send_text(&app, event.text.text);
        } else if (app.allow_control
                && (event.type == SDL_EVENT_MOUSE_BUTTON_DOWN
                    || event.type == SDL_EVENT_MOUSE_BUTTON_UP)
                && event.button.which != SDL_TOUCH_MOUSEID) {
            bool down = event.type == SDL_EVENT_MOUSE_BUTTON_DOWN;
            if (event.button.button == SDL_BUTTON_RIGHT) {
                // Preserve the desktop Back shortcut without injecting a
                // secondary-button touch into Android.
                ns_send_key(&app, down, 4, false, 0);
            } else {
                struct ns_view_layout layout = ns_window_layout(window, &app);
                enum ns_function_button hit = ns_function_button_at(
                    &layout, event.button.x, event.button.y);
                bool in_function_bar = ns_point_in_rect(
                    &layout.function_bar, event.button.x, event.button.y);
                bool consumed = false;
                if (down && in_function_bar) {
                    if (event.button.button == SDL_BUTTON_LEFT
                            && !app.input_state.mouse_active) {
                        app.pressed_function_button = hit;
                    }
                    consumed = true;
                } else if (!down
                        && app.pressed_function_button != NS_FUNCTION_NONE) {
                    enum ns_function_button pressed =
                        app.pressed_function_button;
                    app.pressed_function_button = NS_FUNCTION_NONE;
                    if (event.button.button == SDL_BUTTON_LEFT
                            && hit == pressed) {
                        ns_activate_function_button(&app, pressed);
                    }
                    consumed = true;
                } else if (!down && in_function_bar
                        && !app.input_state.mouse_active) {
                    consumed = true;
                }

                if (!consumed && ns_pointer_input_ready(&app)) {
                    bool valid_transition =
                        !((down && app.input_state.mouse_active)
                          || (!down && !app.input_state.mouse_active));
                    uint16_t x, y;
                    bool mapped = valid_transition && (down
                        ? ns_window_to_device(window, &app,
                                              event.button.x, event.button.y,
                                              &x, &y)
                        : ns_window_to_device_clamped(
                              window, &app, event.button.x, event.button.y,
                              &x, &y));
                    if (mapped) {
                        uint32_t state = SDL_GetMouseState(NULL, NULL);
                        bool sent = ns_send_touch(
                            &app, down ? NS_ACTION_DOWN : NS_ACTION_UP,
                            NS_POINTER_ID_MOUSE, x, y, down ? 1 : 0,
                            ns_android_button(event.button.button),
                            ns_android_buttons(state));
                        if (down) {
                            app.input_state.mouse_active = sent;
                        } else {
                            app.input_state.mouse_active = false;
                        }
                    }
                }
            }
        } else if (app.allow_control
                && event.type == SDL_EVENT_MOUSE_MOTION && event.motion.state
                && event.motion.which != SDL_TOUCH_MOUSEID
                && ns_pointer_input_ready(&app)
                && app.input_state.mouse_active) {
            uint16_t x, y;
            if (ns_window_to_device_clamped(window, &app,
                                            event.motion.x, event.motion.y,
                                            &x, &y)) {
                ns_send_touch(&app, NS_ACTION_MOVE, NS_POINTER_ID_MOUSE, x, y, 1,
                              0, ns_android_buttons(event.motion.state));
            }
        } else if (app.allow_control
                && event.type == SDL_EVENT_MOUSE_WHEEL
                && event.wheel.which != SDL_TOUCH_MOUSEID
                && ns_pointer_input_ready(&app)) {
            uint16_t x, y;
#if NS_SDL3
            float mouse_x = event.wheel.mouse_x;
            float mouse_y = event.wheel.mouse_y;
#else
            int mouse_x_int, mouse_y_int;
            uint32_t mouse_buttons = SDL_GetMouseState(&mouse_x_int, &mouse_y_int);
            float mouse_x = mouse_x_int;
            float mouse_y = mouse_y_int;
#endif
            if (ns_window_to_device(window, &app, mouse_x, mouse_y, &x, &y)) {
                float horizontal = event.wheel.x;
                float vertical = event.wheel.y;
                if (event.wheel.direction == SDL_MOUSEWHEEL_FLIPPED) {
                    horizontal = -horizontal;
                    vertical = -vertical;
                }
#if NS_SDL3
                uint32_t mouse_buttons = SDL_GetMouseState(NULL, NULL);
#endif
                ns_send_scroll(&app, x, y, horizontal, vertical,
                               ns_android_buttons(mouse_buttons));
            }
        } else if (app.allow_control
                && (event.type == SDL_EVENT_FINGER_DOWN
                    || event.type == SDL_EVENT_FINGER_UP
                    || event.type == SDL_EVENT_FINGER_MOTION
                    || event.type == SDL_EVENT_FINGER_CANCELED)) {
            if (ns_pointer_input_ready(&app)
                    && app.source_width && app.source_height) {
                float fx = event.tfinger.x;
                float fy = event.tfinger.y;
                if (fx >= 0 && fy >= 0 && fx <= 1 && fy <= 1) {
                    uint8_t action = event.type == SDL_EVENT_FINGER_DOWN
                                   ? NS_ACTION_DOWN
                                   : event.type == SDL_EVENT_FINGER_UP
                                   ? NS_ACTION_UP
                                   : event.type == SDL_EVENT_FINGER_CANCELED
                                   ? NS_ACTION_CANCEL : NS_ACTION_MOVE;
                    int window_width = 0;
                    int window_height = 0;
                    SDL_GetWindowSize(window, &window_width, &window_height);
                    uint16_t x, y;
                    bool mapped = event.type == SDL_EVENT_FINGER_DOWN
                        ? ns_window_to_device(window, &app,
                                              fx * window_width, fy * window_height,
                                              &x, &y)
                        : ns_window_to_device_clamped(window, &app,
                                                      fx * window_width, fy * window_height,
                                                      &x, &y);
                    if (!mapped) {
                        continue;
                    }
#if NS_SDL3
                    uint64_t finger_id = (uint64_t) event.tfinger.fingerID;
#else
                    uint64_t finger_id = (uint64_t) event.tfinger.fingerId;
#endif
                    bool is_down = action == NS_ACTION_DOWN;
                    bool active = ns_input_touch_active(&app.input_state,
                                                        finger_id);
                    if (action == NS_ACTION_CANCEL) {
                        if (!active) continue;
                        // SDL may identify one canceled finger, but Android ACTION_CANCEL
                        // terminates mouse and every touch in the complete gesture.
                        ns_send_global_cancel(&app);
                        ns_input_state_reset(&app.input_state);
                        continue;
                    }
                    if ((is_down && (active
                            || app.input_state.touch_count
                                == NS_MAX_ACTIVE_TOUCHES))
                            || (!is_down && !active)) {
                        continue;
                    }
                    bool sent = ns_send_touch(&app, action, finger_id,
                                              x, y, event.tfinger.pressure,
                                              0, 0);
                    if (is_down && sent) {
                        ns_input_touch_begin(&app.input_state, finger_id);
                    } else if (action == NS_ACTION_UP) {
                        ns_input_touch_end(&app.input_state, finger_id);
                    }
                }
            }
        }
        // SDL does not guarantee a fresh frame when a retained/black screen is
        // resized or exposed. Redraw after every event so the bar is persistent.
        ns_render_viewer(window, renderer, texture, &app);
    }

cleanup:
    atomic_store(&app.running, false);
    ns_control_queue_stop(&app);
    if (app.video_fd >= 0) shutdown(app.video_fd, SHUT_RDWR);
    if (app.control_fd >= 0) shutdown(app.control_fd, SHUT_RDWR);
    if (app.video_thread_started) pthread_join(app.video_thread, NULL);
    if (app.control_thread_started) pthread_join(app.control_thread, NULL);
    if (app.control_writer_thread_started) {
        pthread_join(app.control_writer_thread, NULL);
    }
    if (app.video_fd >= 0) close(app.video_fd);
    if (app.control_fd >= 0) close(app.control_fd);

    ns_control_queue_clear(&app);
    pthread_mutex_lock(&app.frame_mutex);
    ns_free_frame(app.pending_frame);
    app.pending_frame = NULL;
    pthread_mutex_unlock(&app.frame_mutex);
    pthread_mutex_lock(&app.clipboard_mutex);
    free(app.pending_remote_clipboard);
    app.pending_remote_clipboard = NULL;
    pthread_mutex_unlock(&app.clipboard_mutex);
    ns_clear_pending_local_clipboard(&app);
    ns_clear_remote_clipboard_marker(&app);
    SDL_DestroyTexture(texture);
    SDL_DestroyRenderer(renderer);
    SDL_DestroyWindow(window);
    SDL_Quit();
    pthread_mutex_destroy(&app.frame_mutex);
    pthread_mutex_destroy(&app.clipboard_mutex);
    pthread_cond_destroy(&app.control_queue_cond);
    pthread_mutex_destroy(&app.control_queue_mutex);
    for (size_t i = 0; i < sizeof(helper_challenge); ++i) {
        ((volatile uint8_t *) helper_challenge)[i] = 0;
    }
    return exit_code;
}

static bool
ns_self_test_control_queue(void) {
    int sockets[2] = { -1, -1 };
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, sockets)) return false;

    struct ns_app app;
    memset(&app, 0, sizeof(app));
    app.control_fd = sockets[0];
    app.source_width = 1080;
    app.source_height = 2400;
    app.max_clipboard_bytes = NS_DEFAULT_CLIPBOARD_LIMIT;
    app.allow_control = true;
    app.allow_clipboard = true;
    atomic_init(&app.running, true);
    atomic_init(&app.worker_failure, 0);
    atomic_init(&app.acknowledged_clipboard_sequence, 0);
    pthread_mutex_init(&app.control_queue_mutex, NULL);
    pthread_cond_init(&app.control_queue_cond, NULL);

    bool writer_started = false;
    bool success = false;

    app.allow_control = false;
    if (ns_send_key(&app, true, -2, false, 0)
            || ns_send_touch(&app, NS_ACTION_DOWN, NS_POINTER_ID_MOUSE,
                             1, 1, 1, 1, 1)) {
        goto cleanup;
    }
    app.allow_control = true;
    app.allow_clipboard = false;
    if (ns_send_clipboard(&app, "disabled", NULL)) goto cleanup;
    app.allow_clipboard = true;

    for (uint64_t pointer = 0; pointer < NS_CONTROL_QUEUE_MAX_MESSAGES;
         ++pointer) {
        if (!ns_send_touch(&app, NS_ACTION_MOVE, pointer,
                           1, 1, 1, 0, 0)) {
            goto cleanup;
        }
    }
    if (!ns_send_key(&app, true, -2, false, 0)) goto cleanup;
    pthread_mutex_lock(&app.control_queue_mutex);
    bool pressure_prioritized =
        app.control_queue_count == NS_CONTROL_QUEUE_MAX_MESSAGES
        && app.control_queue_head && app.control_queue_head->essential
        && app.control_queue_head->data[0] == NS_CONTROL_BACK_OR_SCREEN_ON;
    pthread_mutex_unlock(&app.control_queue_mutex);
    if (!pressure_prioritized || atomic_load(&app.worker_failure)) goto cleanup;
    ns_control_queue_clear(&app);

    // A newer move for the same pointer replaces the older queued move, while
    // both key transitions are prioritized, ordered and non-droppable.
    if (!ns_send_touch(&app, NS_ACTION_MOVE, NS_POINTER_ID_MOUSE,
                       12, 34, 1, 0, 1)
            || !ns_send_touch(&app, NS_ACTION_MOVE, NS_POINTER_ID_MOUSE,
                              56, 78, 1, 0, 1)
            || !ns_send_key(&app, true, -2, false, 0)
            || !ns_send_key(&app, false, -2, false, 0)) {
        goto cleanup;
    }
    pthread_mutex_lock(&app.control_queue_mutex);
    bool coalesced = app.control_queue_count == 3
                  && app.control_queue_head
                  && app.control_queue_head->data[0]
                        == NS_CONTROL_BACK_OR_SCREEN_ON
                  && app.control_queue_head->next
                  && app.control_queue_head->next->data[0]
                        == NS_CONTROL_BACK_OR_SCREEN_ON
                  && app.control_queue_tail
                  && ns_read32be(app.control_queue_tail->data + 10) == 56;
    pthread_mutex_unlock(&app.control_queue_mutex);
    if (!coalesced) goto cleanup;

    if (pthread_create(&app.control_writer_thread, NULL,
                       ns_control_writer_main, &app)) {
        goto cleanup;
    }
    writer_started = true;

    uint8_t wake[4];
    uint8_t encoded_move[32];
    if (!ns_recv_all(sockets[1], wake, sizeof(wake))
            || memcmp(wake, (uint8_t[]) { 4, 0, 4, 1 }, sizeof(wake))) {
        goto cleanup;
    }
    if (!ns_recv_all(sockets[1], encoded_move, sizeof(encoded_move))
            || ns_read32be(encoded_move + 10) != 56) goto cleanup;

    if (!ns_send_touch(&app, NS_ACTION_DOWN, NS_POINTER_ID_MOUSE,
                       12, 34, 0.999999f, 1, 1)) {
        goto cleanup;
    }
    uint8_t encoded_touch[32];
    if (!ns_recv_all(sockets[1], encoded_touch, sizeof(encoded_touch))
            || ns_read16be(encoded_touch + 22) != UINT16_MAX) {
        goto cleanup;
    }

    uint64_t clipboard_sequence = 0;
    if (!ns_send_clipboard(&app, "x", &clipboard_sequence)) goto cleanup;
    uint8_t clipboard[15];
    if (!ns_recv_all(sockets[1], clipboard, sizeof(clipboard))
            || clipboard[0] != NS_CONTROL_SET_CLIPBOARD
            || ns_read64be(clipboard + 1) != clipboard_sequence
            || ns_read32be(clipboard + 10) != 1 || clipboard[14] != 'x') {
        goto cleanup;
    }

    if (!ns_activate_function_button(&app, NS_FUNCTION_BACK)
            || !ns_activate_function_button(&app, NS_FUNCTION_HOME)
            || !ns_activate_function_button(&app, NS_FUNCTION_RECENTS)
            || !ns_activate_function_button(&app, NS_FUNCTION_POWER)) {
        goto cleanup;
    }
    uint8_t functions[6 * 14 + 1];
    if (!ns_recv_all(sockets[1], functions, sizeof(functions))) goto cleanup;
    const uint32_t expected_keycodes[] = { 4, 3, 187 };
    size_t offset = 0;
    for (size_t key = 0;
         key < sizeof(expected_keycodes) / sizeof(expected_keycodes[0]);
         ++key) {
        for (uint8_t action = NS_ACTION_DOWN; action <= NS_ACTION_UP;
             ++action) {
            if (functions[offset] != NS_CONTROL_INJECT_KEYCODE
                    || functions[offset + 1] != action
                    || ns_read32be(functions + offset + 2)
                        != expected_keycodes[key]
                    || ns_read32be(functions + offset + 6) != 0
                    || ns_read32be(functions + offset + 10) != 0) {
                goto cleanup;
            }
            offset += 14;
        }
    }
    if (offset != sizeof(functions) - 1
            || functions[offset] != NS_CONTROL_TOGGLE_POWER) {
        goto cleanup;
    }

    success = atomic_load(&app.worker_failure) == 0;

cleanup:
    atomic_store(&app.running, false);
    ns_control_queue_stop(&app);
    if (sockets[0] >= 0) shutdown(sockets[0], SHUT_RDWR);
    if (writer_started) pthread_join(app.control_writer_thread, NULL);
    ns_control_queue_clear(&app);
    pthread_cond_destroy(&app.control_queue_cond);
    pthread_mutex_destroy(&app.control_queue_mutex);
    if (sockets[0] >= 0) close(sockets[0]);
    if (sockets[1] >= 0) close(sockets[1]);
    return success;
}

static int
ns_self_test(void) {
    uint8_t binary[8];
    ns_write64be(binary, UINT64_C(0x0123456789abcdef));
    if (ns_read64be(binary) != UINT64_C(0x0123456789abcdef)) return 1;

    static const uint8_t valid[] = { 'N', 'S', 0xE2, 0x9C, 0x93 };
    static const uint8_t overlong[] = { 0xC0, 0xAF };
    static const uint8_t surrogate[] = { 0xED, 0xA0, 0x80 };
    if (!ns_utf8_valid(valid, sizeof(valid))
            || ns_utf8_valid(overlong, sizeof(overlong))
            || ns_utf8_valid(surrogate, sizeof(surrogate))) return 1;

    SDL_FRect portrait = ns_destination_rect(1000, 500, 1080, 2400);
    if (portrait.h != 500 || portrait.w < 224 || portrait.w > 226) return 1;

    struct ns_view_layout layout = ns_view_layout(
        1000, 500, NS_FUNCTION_BAR_HEIGHT, 1080, 2400);
    if (layout.video_bounds.h != 436
            || layout.function_bar.y != 436
            || layout.function_bar.h != NS_FUNCTION_BAR_HEIGHT
            || fabsf(layout.video_destination.w
                      / layout.video_destination.h - 1080.0f / 2400.0f)
                > 0.0001f) {
        return 1;
    }
    for (int i = 0; i < NS_FUNCTION_BUTTON_COUNT; ++i) {
        SDL_FRect button = layout.function_buttons[i];
        if (ns_function_button_at(&layout, button.x + button.w / 2,
                                  button.y + button.h / 2)
                != (enum ns_function_button) i) {
            return 1;
        }
    }
    float button_gap = (layout.function_buttons[0].x
                        + layout.function_buttons[0].w
                        + layout.function_buttons[1].x) / 2;
    if (ns_function_button_at(
            &layout, button_gap,
            layout.function_buttons[0].y
                + layout.function_buttons[0].h / 2) != NS_FUNCTION_NONE
            || ns_function_button_at(&layout, 500, 100) != NS_FUNCTION_NONE
            || ns_function_button_at(&layout, 500, 500) != NS_FUNCTION_NONE) {
        return 1;
    }
    uint16_t mapped_x = 0;
    uint16_t mapped_y = 0;
    float video_center_x = layout.video_destination.x
                         + layout.video_destination.w / 2;
    float video_center_y = layout.video_destination.y
                         + layout.video_destination.h / 2;
    if (!ns_view_to_device(1000, 500, 1080, 2400,
                           video_center_x, video_center_y, false,
                           &mapped_x, &mapped_y)
            || mapped_x < 539 || mapped_x > 540
            || mapped_y < 1199 || mapped_y > 1200
            || ns_view_to_device(1000, 500, 1080, 2400, 500, 450, false,
                                 &mapped_x, &mapped_y)
            || !ns_view_to_device(1000, 500, 1080, 2400, 500, 450, true,
                                  &mapped_x, &mapped_y)
            || mapped_y < 2390 || mapped_y >= 2400) {
        return 1;
    }
    if (ns_android_keycode(SDLK_F12, 0) != NS_KEYCODE_TOGGLE_POWER
            || ns_android_keycode(SDLK_ESCAPE, 0) != 4) {
        return 1;
    }

    uint8_t touch[32] = { NS_CONTROL_INJECT_TOUCH, NS_ACTION_DOWN };
    ns_write64be(touch + 2, NS_POINTER_ID_MOUSE);
    ns_write32be(touch + 10, 12);
    ns_write32be(touch + 14, 34);
    ns_write16be(touch + 18, 1080);
    ns_write16be(touch + 20, 2400);
    if (touch[0] != 2 || touch[1] != 0 || ns_read32be(touch + 10) != 12
            || ns_read16be(touch + 20) != 2400) return 1;

    if (!ns_ffmpeg_lgpl_compatible("--enable-shared --disable-programs")
            || ns_ffmpeg_lgpl_compatible("--enable-shared --enable-gpl")
            || ns_ffmpeg_lgpl_compatible("--enable-nonfree")) return 1;
    if (ns_scroll_fixed(16) != INT16_MAX || ns_scroll_fixed(-16) != INT16_MIN) return 1;
    if (NS_MAX_DEVICE_CLIPBOARD_WIRE != NS_MAX_CONTROL_CLIPBOARD_WIRE + 9) return 1;
    if (!ns_decoder_packet_size_valid(NS_MAX_PACKET_SIZE / 2,
                                      NS_MAX_PACKET_SIZE / 2)
            || ns_decoder_packet_size_valid(NS_MAX_PACKET_SIZE,
                                             NS_MAX_PACKET_SIZE)
            || ns_decoder_packet_size_valid(NS_MAX_PACKET_SIZE + 1U, 0)) {
        return 1;
    }
    if (!ns_self_test_decoder_packet_preparation()) return 1;
    struct ns_app input_app;
    memset(&input_app, 0, sizeof(input_app));
    input_app.allow_control = true;
    input_app.source_width = 1080;
    input_app.source_height = 2400;
    atomic_init(&input_app.running, true);
    atomic_init(&input_app.worker_failure, 0);
    atomic_init(&input_app.video_generation, 1);
    pthread_mutex_init(&input_app.control_queue_mutex, NULL);
    pthread_cond_init(&input_app.control_queue_cond, NULL);
    input_app.applied_video_generation = 1;
    input_app.pointer_input_ready = true;
    input_app.input_state.mouse_active = true;
    if (!ns_input_touch_begin(&input_app.input_state, 11)
            || !ns_input_touch_begin(&input_app.input_state, 22)
            || !ns_pointer_input_ready(&input_app)
            || !ns_send_touch(&input_app, NS_ACTION_MOVE, 11, 10, 10, 1, 0, 0)
            || !ns_send_touch(&input_app, NS_ACTION_MOVE, 22, 20, 20, 1, 0, 0)) {
        return 1;
    }
    atomic_store(&input_app.video_generation, 2);
    if (ns_pointer_input_ready(&input_app)) return 1;
    ns_reconcile_video_generation(&input_app);
    if (input_app.pointer_input_ready || input_app.input_state.mouse_active
            || input_app.input_state.touch_count
            || ns_input_touch_end(&input_app.input_state, 11)
            || !input_app.control_queue_head
            || input_app.control_queue_count != 1
            || input_app.control_queue_head->data[0] != NS_CONTROL_INJECT_TOUCH
            || input_app.control_queue_head->data[1] != NS_ACTION_CANCEL) return 1;
    // Delayed MOVE/UP for either of the two canceled touches are orphans. Only
    // a fresh DOWN may re-enter the active set.
    if (ns_input_touch_active(&input_app.input_state, 11)
            || ns_input_touch_active(&input_app.input_state, 22)) return 1;
    input_app.pointer_input_ready = true;
    if (!ns_pointer_input_ready(&input_app)
            || !ns_input_touch_begin(&input_app.input_state, 33)
            || !ns_input_touch_active(&input_app.input_state, 33)
            || !ns_input_touch_end(&input_app.input_state, 33)
            || ns_input_touch_active(&input_app.input_state, 33)) return 1;
    ns_control_queue_clear(&input_app);
    pthread_cond_destroy(&input_app.control_queue_cond);
    pthread_mutex_destroy(&input_app.control_queue_mutex);
    struct ns_app clipboard_app;
    memset(&clipboard_app, 0, sizeof(clipboard_app));
    atomic_init(&clipboard_app.acknowledged_clipboard_sequence, 0);
    clipboard_app.pending_local_clipboard = strdup("local");
    clipboard_app.pending_local_clipboard_sequence = 7;
    clipboard_app.pending_local_clipboard_deadline_ms = 200;
    ns_refresh_clipboard_ack(&clipboard_app, 199);
    if (!clipboard_app.pending_local_clipboard) return 1;
    ns_refresh_clipboard_ack(&clipboard_app, 200);
    if (clipboard_app.pending_local_clipboard
            || clipboard_app.pending_local_clipboard_sequence
            || clipboard_app.pending_local_clipboard_deadline_ms) return 1;
    clipboard_app.pending_local_clipboard = strdup("new-local");
    clipboard_app.pending_local_clipboard_sequence = 8;
    clipboard_app.pending_local_clipboard_deadline_ms = 300;
    atomic_store(&clipboard_app.acknowledged_clipboard_sequence, 8);
    ns_refresh_clipboard_ack(&clipboard_app, 201);
    if (clipboard_app.pending_local_clipboard) return 1;
    clipboard_app.remote_clipboard_reflection = strdup("remote");
    clipboard_app.pending_remote_clipboard_reflections = 2;
    clipboard_app.remote_clipboard_reflection_deadline_ms = 200;
    if (!clipboard_app.remote_clipboard_reflection
            || ns_suppress_reflected_remote_clipboard(
                &clipboard_app, "simultaneous-local-edit", 100)
            || !clipboard_app.remote_clipboard_reflection
            || !ns_suppress_reflected_remote_clipboard(
                &clipboard_app, "remote", 150)
            || !clipboard_app.remote_clipboard_reflection
            || clipboard_app.pending_remote_clipboard_reflections != 1
            || !ns_suppress_reflected_remote_clipboard(
                &clipboard_app, "remote", 151)
            || clipboard_app.remote_clipboard_reflection) return 1;
    clipboard_app.remote_clipboard_reflection = strdup("late-remote");
    clipboard_app.pending_remote_clipboard_reflections = 1;
    clipboard_app.remote_clipboard_reflection_deadline_ms = 200;
    if (!clipboard_app.remote_clipboard_reflection
            || ns_suppress_reflected_remote_clipboard(
                &clipboard_app, "new-local-edit", 201)
            || clipboard_app.remote_clipboard_reflection) return 1;
    if (!avcodec_find_decoder(AV_CODEC_ID_H264)
            || !avcodec_find_decoder(AV_CODEC_ID_HEVC)
            || !avcodec_find_decoder(AV_CODEC_ID_AV1)) return 1;
    if (!ns_self_test_control_queue()) return 1;

    puts("notisync-screen-helper self-test passed");
    return 0;
}

int
main(int argc, char **argv) {
    struct ns_options options;
    if (!ns_parse_options(argc, argv, &options)) {
        ns_usage(stderr, argv[0]);
        return NS_EXIT_USAGE;
    }
    if (options.self_test) {
        return ns_self_test();
    }
    if (options.check_runtime) {
        const char *configuration = avcodec_configuration();
        if (!ns_ffmpeg_lgpl_compatible(configuration)) {
            fprintf(stderr, "FFmpeg runtime is GPL/nonfree and cannot be packaged by NotiSync\n");
            return NS_EXIT_RUNTIME;
        }
        printf("FFmpeg runtime accepted: %s\n", configuration);
        return 0;
    }

    signal(SIGINT, ns_signal_handler);
    signal(SIGTERM, ns_signal_handler);
    signal(SIGPIPE, SIG_IGN);
    return ns_run_viewer(&options);
}
