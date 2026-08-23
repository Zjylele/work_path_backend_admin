package com.workpath.backend.vo;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.Data;

import java.util.List;

@Data
public class PageVO<T> {

    private List<T> list;

    private Long total;

    private Long page;

    private Long pageSize;

    public static <T> PageVO<T> of(IPage<T> page) {
        PageVO<T> vo = new PageVO<>();
        vo.setList(page.getRecords());
        vo.setTotal(page.getTotal());
        vo.setPage(page.getCurrent());
        vo.setPageSize(page.getSize());
        return vo;
    }

}
