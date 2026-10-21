package com.ys101.storage.common;

import lombok.Getter;

/**
 * 可预期的业务异常。与系统异常的区别：它不需要打印完整堆栈，
 * 只需要把 code 和 msg 返回给调用方。
 */
@Getter
public class BizException extends RuntimeException {

    private final int code;

    public BizException(ResultCode rc) {
        super(rc.getMsg());
        this.code = rc.getCode();
    }

    public BizException(ResultCode rc, String msg) {
        super(msg);
        this.code = rc.getCode();
    }

    public static BizException of(ResultCode rc) {
        return new BizException(rc);
    }
}
