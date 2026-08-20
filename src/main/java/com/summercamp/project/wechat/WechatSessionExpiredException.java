package com.summercamp.project.wechat;

public class WechatSessionExpiredException extends RuntimeException {

    public WechatSessionExpiredException(String message, Throwable cause) {
        super(message, cause);
    }
}
