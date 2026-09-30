package com.workpath.backend.dto;

import lombok.Data;

/** 管理员确认整改（1→2）的备注，可空。 */
@Data
public class CloseOrderDTO {

    private String reviewNote;
}
