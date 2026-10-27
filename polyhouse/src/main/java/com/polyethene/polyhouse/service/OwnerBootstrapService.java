package com.polyethene.polyhouse.service;

import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.mapper.OwnerBootstrapMapper;
import com.polyethene.polyhouse.model.entity.OwnerBootstrapRecord;
import com.polyethene.polyhouse.model.entity.OwnerBootstrapTarget;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** 仅由独立运维上下文显式装配，普通 Web 应用不注册此服务，也没有角色变更接口。 */
public class OwnerBootstrapService {
    private final OwnerBootstrapMapper mapper;
    private final AuthRedisStore sessions;

    /** 复用数据库事务与现有三端撤销，脚本不直接拼 SQL 或操作 Redis 键。 */
    public OwnerBootstrapService(OwnerBootstrapMapper mapper, AuthRedisStore sessions) {
        this.mapper = mapper;
        this.sessions = sessions;
    }

    /** 只核对不修改；预览结果不是预约，实际执行会重新检查所有条件。 */
    @Transactional(readOnly = true)
    public OwnerBootstrapTarget inspect(long userId, String username) {
        validate(userId, username);
        try {
            schema();
            return checked(mapper.record(), mapper.target(userId), mapper.ownerId(), userId, username);
        } catch (DataAccessException exception) {
            throw unavailable();
        }
    }

    /**
     * 固定顺序“初始化槽 -> 用户 -> 屋主索引”串行竞争；角色/首次审计一起提交。
     * Redis 失败回滚 MySQL；Redis 不参与事务，已撤销的会话可能需要重新登录，但不能带着旧会话完成授予。
     * @return true 为首次确认完成，false 为同一屋主重复执行；重复执行仍撤销会话，便于不确定结果后安全重试
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean initialize(long userId, String username, String operator) {
        validate(userId, username);
        if (operator == null || operator.isBlank() || operator.length() > 128
                || operator.codePoints().anyMatch(Character::isISOControl)) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "缺少有效的操作系统执行人信息");
        }
        try {
            schema();
            OwnerBootstrapRecord record = mapper.lockRecord();
            OwnerBootstrapTarget target = checked(record, mapper.lockTarget(userId), mapper.lockOwnerId(), userId, username);
            boolean first = record.userId() == null;
            if (target.role() != UserRoleEnum.OWNER
                    && mapper.promote(userId, target.role(), UserRoleEnum.OWNER) != 1) {
                throw unavailable();
            }
            if (first && mapper.recordInitialization(userId, username, target.role(), operator) != 1) {
                throw unavailable();
            }
            sessions.removeAllSessions(userId);
            return first;
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.CONFLICT, "屋主唯一性冲突，未完成初始化，请核对数据库状态");
        } catch (DataAccessException exception) {
            throw unavailable();
        }
    }

    private void schema() {
        String expression = mapper.guardExpression();
        // MySQL 元数据还会转义字符串引号；只规范已知展示格式后精确比较，不能放宽为“存在同名列”即可。
        String normalized = expression == null ? "" : expression.replaceAll("[\\s`()]+", "")
                .replace("_utf8mb4", "").replace("\\'", "'");
        if (mapper.uniqueGuardCount() != 1 || !normalized.equalsIgnoreCase("casewhenrole='OWNER'then1elseNULLend")) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "屋主数据库保障未就绪，请先核对 004 迁移");
        }
    }

    private static OwnerBootstrapTarget checked(OwnerBootstrapRecord record, OwnerBootstrapTarget target,
                                                Long owner, long userId, String username) {
        if (record == null) throw unavailable();
        if (target == null || target.id() != userId || !Objects.equals(target.username(), username)) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "用户编号和用户名不匹配，请按个人中心原样填写");
        }
        if (target.status() != 1 || target.deleted() != 0 || target.role() == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "目标账号已禁用、注销或角色异常，不能初始化");
        }
        if ((owner != null && owner != userId) || (record.userId() != null && record.userId() != userId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "已有其他屋主或初始化记录，不能覆盖；请走人工恢复或移交流程");
        }
        if (record.userId() != null && (target.role() != UserRoleEnum.OWNER || !Objects.equals(owner, userId))) {
            throw new BusinessException(ErrorCode.CONFLICT, "屋主记录与账号角色不一致，停止初始化，请人工核对");
        }
        return target;
    }

    private static void validate(long userId, String username) {
        // 注册后数据库账号固定为小写 ASCII；拒绝自动裁剪或纠正，避免运维误认目标。
        if (userId <= 0 || username == null || !username.matches("[a-z0-9_]{4,32}")) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "编号必须为正整数，用户名须按个人中心的小写账号原样填写");
        }
    }

    private static BusinessException unavailable() {
        return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "屋主初始化未完成，请核对迁移或存储服务后重新检查");
    }
}
