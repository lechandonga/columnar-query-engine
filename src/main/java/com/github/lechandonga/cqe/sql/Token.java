package com.github.lechandonga.cqe.sql;

/**
 * @param position 基于 0 的源码字符偏移，用于错误定位
 */
public record Token(TokenType type, String text, int position) {
}
