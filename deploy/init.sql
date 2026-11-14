-- Music Killer 数据库初始化脚本
-- 由 MySQL 容器在「数据卷为空」时自动执行（见 docker-compose.yml 的 initdb 挂载）
-- 库名 drny，与 application-dev.yml / application-prod.yml 保持一致

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS `drny`
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_general_ci;

USE `drny`;

-- 用户表（UserMapper.xml 使用）
CREATE TABLE IF NOT EXISTS `user` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '唯一id',
  `avatar`      VARCHAR(500) DEFAULT NULL COMMENT '头像',
  `username`    VARCHAR(64)  NOT NULL COMMENT '用户名',
  `nickname`    VARCHAR(64)  DEFAULT NULL COMMENT '昵称',
  `password`    VARCHAR(64)  NOT NULL COMMENT '密码(md5)',
  `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `last_ip`     VARCHAR(64)  DEFAULT NULL COMMENT '最后登录ip',
  `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态 1启用 0禁用',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户';

-- 图片表（ImageMapper.xml / 上传接口使用）
CREATE TABLE IF NOT EXISTS `image` (
  `id`          INT          NOT NULL AUTO_INCREMENT COMMENT '唯一id',
  `name`        VARCHAR(255) DEFAULT NULL COMMENT '图片名称',
  `size`        INT          DEFAULT NULL COMMENT '图片大小',
  `md5`         VARCHAR(64)  DEFAULT NULL COMMENT '图片md5',
  `sha1`        VARCHAR(64)  DEFAULT NULL COMMENT '图片sha1',
  `mime`        VARCHAR(64)  DEFAULT NULL COMMENT '图片类型',
  `url`         VARCHAR(500) DEFAULT NULL COMMENT '图片真实地址',
  `ext_id`      INT          DEFAULT NULL COMMENT '外部id',
  `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='图片集合';

-- 直播/电台条目（LiveMapper 使用）
CREATE TABLE IF NOT EXISTS `live_item` (
  `id`   INT          NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(255) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='live_item';

-- 可选测试账号：test / 123456
INSERT IGNORE INTO `user` (`username`, `nickname`, `password`, `last_ip`, `status`)
VALUES ('test', 'test', MD5('123456'), '127.0.0.1', 1);
