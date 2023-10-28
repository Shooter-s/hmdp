package com.hmdp.dto;

import lombok.Data;

import java.util.List;

/**
 * ClassName: ScrollResult
 * Package: com.hmdp.dto
 * Description:
 *
 * @Author:Shooter
 * @Create 2023/10/28 11:38
 * @Version 1.0
 */
@Data
public class ScrollResult {

    private List<?> list;
    private Long minTime;
    private Integer offset;

}
