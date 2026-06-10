CREATE TABLE users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '用户唯一标识',
    username VARCHAR(255) NOT NULL UNIQUE COMMENT '用户名，唯一',
    password VARCHAR(255) NOT NULL COMMENT '加密后的密码',
    role ENUM('USER', 'ADMIN') NOT NULL DEFAULT 'USER' COMMENT '用户角色',
    org_tags VARCHAR(255) DEFAULT NULL COMMENT '用户所属组织标签，多个用逗号分隔',
    primary_org VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '用户主组织标签',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_username (username) COMMENT '用户名索引'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户表';

CREATE TABLE organization_tags (
    tag_id VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin PRIMARY KEY COMMENT '标签唯一标识',
    name VARCHAR(100) NOT NULL COMMENT '标签名称',
    description TEXT COMMENT '描述',
    parent_tag VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '父标签ID',
    created_by BIGINT NOT NULL COMMENT '创建者ID',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    FOREIGN KEY (parent_tag) REFERENCES organization_tags (tag_id) ON DELETE SET NULL,
    FOREIGN KEY (created_by) REFERENCES users (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '组织标签表';

CREATE TABLE file_upload (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    file_md5 VARCHAR(32) NOT NULL COMMENT '文件 MD5',
    file_name VARCHAR(255) NOT NULL COMMENT '文件名称',
    total_size BIGINT NOT NULL COMMENT '文件大小',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '上传状态',
    user_id VARCHAR(64) NOT NULL COMMENT '用户 ID',
    org_tag VARCHAR(50) DEFAULT NULL COMMENT '组织标签',
    is_public BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否公开',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    merged_at TIMESTAMP NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '合并时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_md5_user (file_md5, user_id),
    INDEX idx_user (user_id),
    INDEX idx_org_tag (org_tag)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '文件上传记录';

CREATE TABLE chunk_info (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '分块记录唯一标识',
    file_md5 VARCHAR(32) NOT NULL COMMENT '关联的文件MD5值',
    chunk_index INT NOT NULL COMMENT '分块序号',
    chunk_md5 VARCHAR(32) NOT NULL COMMENT '分块的MD5值',
    storage_path VARCHAR(255) NOT NULL COMMENT '分块在存储系统中的路径'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '文件分块信息表';

CREATE TABLE document_vectors (
    vector_id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '向量记录唯一标识',
    file_md5 VARCHAR(32) NOT NULL COMMENT '关联的文件MD5值',
    chunk_id INT NOT NULL COMMENT '文本分块序号',
    text_content TEXT COMMENT '文本内容',
    model_version VARCHAR(32) COMMENT '向量模型版本',
    user_id VARCHAR(64) NOT NULL COMMENT '上传用户ID',
    org_tag VARCHAR(50) COMMENT '文件所属组织标签',
    is_public BOOLEAN NOT NULL DEFAULT FALSE COMMENT '文件是否公开'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '文档向量存储表';

CREATE TABLE `chat_messages` (
    `id` bigint NOT NULL AUTO_INCREMENT,
    `content` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    `role` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    `session_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    `timestamp` datetime(6) NOT NULL,
    `user_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    PRIMARY KEY (`id`) USING BTREE,
    INDEX `idx_msg_session_id` (`session_id` ASC) USING BTREE,
    INDEX `idx_msg_timestamp` (`timestamp` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '记录每个窗口的每轮对话' ROW_FORMAT = Dynamic;

CREATE TABLE `chat_sessions` (
    `id` bigint NOT NULL AUTO_INCREMENT,
    `active` bit(1) NOT NULL,
    `created_at` datetime(6) NOT NULL,
    `message_count` int NOT NULL,
    `session_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    `title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
    `updated_at` datetime(6) NOT NULL,
    `user_id` bigint NOT NULL,
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE INDEX `UKnbds8mvm10f5rs8rj2nlx98y9` (`session_id` ASC) USING BTREE,
    INDEX `idx_session_user_id` (`user_id` ASC) USING BTREE,
    INDEX `idx_session_updated_at` (`updated_at` ASC) USING BTREE,
    CONSTRAINT `FK82ky97glaomlmhjqae1d0esmy` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB AUTO_INCREMENT = 2 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '记录每个对话窗口' ROW_FORMAT = Dynamic;