package org.example.repository.embedding.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EmbeddingResponse {
    private EmbeddingOutput output;

    private EmbeddingUsage usage;
}
