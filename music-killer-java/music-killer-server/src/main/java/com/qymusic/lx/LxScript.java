package com.qymusic.lx;

/**
 * 一个洛雪（lx-music）v4 自定义音源脚本。
 *
 * @param id      脚本标识（文件名去掉 .js）
 * @param name    脚本名（取脚本头部 @name，取不到时用文件名）
 * @param version 版本（取 @version）
 * @param author  作者（取 @author）
 * @param origin  来源：builtin（随 jar 内置） / external（用户导入）
 * @param code    脚本源码
 */
public record LxScript(String id, String name, String version, String author, String origin, String code) {

    public boolean external() {
        return "external".equals(origin);
    }
}
