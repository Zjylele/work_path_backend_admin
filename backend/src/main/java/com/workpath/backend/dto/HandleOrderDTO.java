package com.workpath.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 员工提交整改处理说明（0→1）。 */
@Data
public class HandleOrderDTO {

    @NotBlank(message = "处理说明不能为空")
    private String handleNote;
}
