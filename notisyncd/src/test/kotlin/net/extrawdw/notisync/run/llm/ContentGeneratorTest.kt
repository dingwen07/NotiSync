package net.extrawdw.notisync.run.llm

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import net.extrawdw.notisync.desktop.config.LlmConfig

class ContentGeneratorTest {
    @Test
    fun `OpenAI compatible response is parsed and bearer stays in the header`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var authorization: String? = null
        var requestBody = ""
        server.createContext("/v1/chat/completions") { exchange ->
            authorization = exchange.requestHeaders.getFirst("Authorization")
            requestBody = exchange.requestBody.bufferedReader().use { it.readText() }
            val response = """{"choices":[{"message":{"content":"{\"title\":\"Build\",\"text\":\"Complete\"}"}}]}"""
                .encodeToByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val generator = OpenAiCompatibleContentGenerator(
                LlmConfig("http://127.0.0.1:${server.address.port}/v1", "test", "secret"),
            )
            val generated = generator.generate(
                GenerationContext(
                    "COMPLETED",
                    listOf("make", "x".repeat(20_000) + "ARGV_SENTINEL"),
                    Path.of("/", "p".repeat(5_000) + "PWD_SENTINEL"),
                    "file",
                    "done",
                    0,
                ),
            )
            assertEquals("Build", generated.title)
            assertEquals("Bearer secret", authorization)
            assertFalse(requestBody.contains("secret"))
            assertFalse(requestBody.contains("ARGV_SENTINEL"))
            assertFalse(requestBody.contains("PWD_SENTINEL"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `tree excludes sensitive and bulky directories and symlinks`() {
        val root = Files.createTempDirectory("nsrun-tree")
        Files.writeString(root.resolve("visible.txt"), "ok")
        Files.createDirectory(root.resolve(".git"))
        Files.writeString(root.resolve(".git/secret"), "no")
        Files.createSymbolicLink(root.resolve("link"), root.resolve("visible.txt"))
        val tree = CappedTree.collect(root, LlmConfig("https://example.test/v1", "m", "k"))
        assertTrue(tree.contains("visible.txt"))
        assertFalse(tree.contains(".git"))
        assertFalse(tree.contains("link"))
    }

    @Test
    fun `tree and output caps count UTF-8 bytes`() {
        val root = Files.createTempDirectory("nsrun-unicode-tree")
        Files.writeString(root.resolve("界界界界.txt"), "ok")
        val config = LlmConfig(
            "https://example.test/v1", "m", "k", treeBytes = 18, outputBytes = 5,
        )
        val tree = CappedTree.collect(root, config)
        assertTrue(tree.encodeToByteArray().size <= 18)
        val tail = CappedTree.tailUtf8("a界b界c", 5)
        assertTrue(tail.encodeToByteArray().size <= 5)
        assertFalse(tail.contains('\uFFFD'))
        val prefix = CappedTree.prefixUtf8("a界b界c", 5)
        assertTrue(prefix.encodeToByteArray().size <= 5)
        assertFalse(prefix.contains('\uFFFD'))
    }

    @Test
    fun `configured LLM limits cannot exceed privacy ceilings`() {
        assertThrows(IllegalArgumentException::class.java) {
            LlmConfig(
                "https://example.test/v1",
                "m",
                "k",
                treeDepth = 3,
                treeEntries = 201,
                treeBytes = 32 * 1024 + 1,
                outputBytes = 16 * 1024 + 1,
            ).validate()
        }
    }
}
