package com.workpath.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 员工提交申诉（0→3）。 */
@Data
public class AppealOrderDTO {

    @NotBlank(message = "申诉理由不能为空")
    private String appealReason;
}
