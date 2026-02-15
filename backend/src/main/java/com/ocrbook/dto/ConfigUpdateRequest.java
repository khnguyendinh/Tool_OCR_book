package com.ocrbook.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConfigUpdateRequest {
    private Integer splitParts;
    private String splitDir;
    private String outputDir;
}
