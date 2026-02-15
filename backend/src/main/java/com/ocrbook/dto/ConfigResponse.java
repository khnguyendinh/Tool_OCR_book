package com.ocrbook.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConfigResponse {
    private int splitParts;
    private String splitDir;
    private String outputDir;
}
