package com.workpath.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UserPasswordDTO {

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 50, message = "密码长度需为 6~50 位")
    private String password;

}
