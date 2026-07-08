package com.paicli.platform.server.knowledge;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredDocumentChunkerTest {
    private final StructuredDocumentChunker chunker = new StructuredDocumentChunker();

    @Test
    void preservesHeadingHierarchyAndStructuralBlocks() {
        String markdown = """
                # Architecture
                Intro sentence. Another sentence.

                ## Reliability
                - persist before execution
                - reuse idempotency key

                | rule | value |
                | --- | --- |
                | retry | safe only |

                ```java
                record Run(String id) {}
                ```

                ## Retrieval
                BM25 and vector retrieval are fused.
                """;

        var chunks = chunker.chunk(markdown);

        assertThat(chunks).extracting(StructuredDocumentChunker.Chunk::heading)
                .contains("Architecture", "Architecture > Reliability", "Architecture > Retrieval");
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.text()).isNotBlank();
            assertThat(chunk.text().length()).isLessThanOrEqualTo(StructuredDocumentChunker.MAX_CHARS);
        });
        assertThat(chunks.stream().map(StructuredDocumentChunker.Chunk::text)
                .reduce("", (left, right) -> left + "\n" + right))
                .contains("persist before execution", "| retry | safe only |", "record Run");
    }

    @Test
    void splitsLongParagraphAtSentenceBoundariesWithinBudget() {
        String sentence = "A recoverable operation persists intent before execution. ";
        String content = "# Long\n\n" + sentence.repeat(100);

        var chunks = chunker.chunk(content);

        assertThat(chunks).hasSizeGreaterThan(2)
                .allSatisfy(chunk -> assertThat(chunk.text().length())
                        .isLessThanOrEqualTo(StructuredDocumentChunker.MAX_CHARS));
    }
}
