package com.workpath.backend.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 管理员复核申诉（3→2）。accept=false 维持原结论，true 接受申诉。 */
@Data
public class ReviewOrderDTO {

    @NotNull(message = "复核结论不能为空")
    private Boolean accept;

    private String reviewNote;
}
