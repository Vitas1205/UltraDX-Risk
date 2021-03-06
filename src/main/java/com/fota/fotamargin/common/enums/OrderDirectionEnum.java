package com.fota.fotamargin.common.enums;

import lombok.Getter;


/**
 * @author taoyuanming
 * Created on 2018/8/4
 * Description
 */
public enum OrderDirectionEnum {

    /**
     * εε
     */
    ASK(1, "ask"),
    /**
     * δΉ°ε
     */
    BID(2, "bid"),
    ;
    @Getter
    private Integer code;
    private String desc;

    OrderDirectionEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

}
