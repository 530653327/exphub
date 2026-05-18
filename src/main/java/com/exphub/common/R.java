package com.exphub.common;

import lombok.Data;
import java.io.Serializable;

@Data
public class R<T> implements Serializable {
    private int code;
    private String message;
    private T data;

    public static <T> R<T> ok() {
        return ok(null);
    }

    public static <T> R<T> ok(T data) {
        R<T> r = new R<>();
        r.setCode(0);
        r.setMessage("success");
        r.setData(data);
        return r;
    }

    public static <T> R<T> ok(T data, String message) {
        R<T> r = new R<>();
        r.setCode(0);
        r.setMessage(message);
        r.setData(data);
        return r;
    }

    public static <T> R<T> fail(String message) {
        R<T> r = new R<>();
        r.setCode(1);
        r.setMessage(message);
        return r;
    }

    public static <T> R<T> fail(int code, String message) {
        R<T> r = new R<>();
        r.setCode(code);
        r.setMessage(message);
        return r;
    }
}