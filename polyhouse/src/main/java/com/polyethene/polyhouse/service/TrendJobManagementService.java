package com.polyethene.polyhouse.service;

import com.polyethene.polyhouse.common.annotation.RequireRole;
import com.polyethene.polyhouse.common.constant.*;
import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.model.dto.*;
import com.polyethene.polyhouse.model.vo.*;
import com.polyethene.polyhouse.service.trend.TrendOwnerAuthorization;
import jakarta.validation.Validator;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/** HTTP管理请求的最新会话复核与事务边界，执行器只在受理提交之后唤醒。 */
@Service
public class TrendJobManagementService {
    private final TrendOwnerAuthorization owners;
    private final TrendJobStore store;
    private final TrendJobRunner runner;
    private final Validator validator;
    private final TransactionTemplate transaction;

    /** 会话行锁在任务槽之前，保持与执行/发布相同的锁顺序。 */
    public TrendJobManagementService(TrendOwnerAuthorization owners, TrendJobStore store, TrendJobRunner runner,
                                     Validator validator, PlatformTransactionManager manager) {
        this.owners = owners; this.store = store; this.runner = runner; this.validator = validator;
        transaction = new TransactionTemplate(manager); transaction.setTimeout(5);
    }

    /** 相同UUID即使响应丢失也能核对；提交后再启动线程，不让线程读到未提交的任务。 */
    @RequireRole(UserRoleEnum.INSIDER)
    public TrendJobSubmissionVO submit(TrendJobSubmitDTO request, String token) {
        validate(request);
        try {
            var submission = transaction.execute(status -> store.submit(request.requestId(), owners.lockSession(token)));
            runner.wake();
            return submission;
        } catch (DataAccessException | TransactionException failure) { throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE); }
    }

    /** 读内部任务也核对当前会话，不把离线任务状态变成公开枚举入口。 */
    public TrendJobVO query(TrendJobQueryDTO request, String token) {
        validate(request);
        try {
            return transaction.execute(status -> { owners.lockSession(token); return store.query(request.jobId()); });
        } catch (DataAccessException | TransactionException failure) { throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE); }
    }

    /** 屋主只读查询每日自动采集配置、今日受理状态与最近 5 条任务记录。 */
    public TrendScheduleStatusVO scheduleStatus(String token) {
        try {
            return transaction.execute(status -> { owners.lockSession(token); return store.scheduleStatus(); });
        } catch (DataAccessException | TransactionException failure) { throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE); }
    }

    private void validate(Object request) {
        if (request == null || !validator.validate(request).isEmpty()) throw new BusinessException(ErrorCode.INVALID_PARAMETER);
    }
}
