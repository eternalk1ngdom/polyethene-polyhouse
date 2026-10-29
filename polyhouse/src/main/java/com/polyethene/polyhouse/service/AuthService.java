package com.polyethene.polyhouse.service;

import com.polyethene.polyhouse.model.dto.LoginDTO;
import com.polyethene.polyhouse.model.dto.RegisterDTO;
import com.polyethene.polyhouse.model.vo.LoginVO;
import com.polyethene.polyhouse.model.vo.UserProfileVO;
import com.polyethene.polyhouse.model.vo.UserGrowthVO;

/**
 * 组织账号与会话业务，注册登录接收 DTO、资料返回安全 VO，撤销操作接收原始 Token。
 * HTTP 绑定由 Controller 负责，业务层必须独立完成会话和账号状态核验。
 */
public interface AuthService {

    /**
     * 保留内部历史夹具/兼容调用的普通用户创建能力，不再映射到公共 HTTP 注册。
     * 网页开户必须调用独立 AccountProvisioningService，不能借此绕过屋主授权与首次改密。
     *
     * @param request 注册必需信息，不允许客户端设置角色或状态
     * @return 已持久化的安全资料，不自动建立登录会话
     */
    UserProfileVO register(RegisterDTO request);

    /**
     * 核验凭据并建立指定终端的 JWT 白名单会话，凭据错误时允许立即重新尝试。
     *
     * @param request 登录凭据和可选终端标识
     * @param clientIp 由服务端连接信息提供的审计 IP，允许 null；不能直接信任客户端转发头
     * @return 仅在白名单保存成功后返回的令牌与安全资料
     */
    LoginVO login(LoginDTO request, String clientIp);

    /**
     * 核对签名、白名单和当前数据库账号状态，返回数据库中的最新角色资料。
     *
     * @param token 不带 Bearer 前缀的原始访问令牌
     * @return 当前有效会话对应的安全资料，供后续拦截器及 me 接口使用
     */
    UserProfileVO getCurrentUser(String token);

    /** 验签及核对白名单后读取自己的成长快照，不接收浏览器指定的用户编号或奖励数值。 */
    UserGrowthVO getGrowthStatus(String token);

    /**
     * 只撤销与传入令牌编号一致的会话，避免旧令牌删除同端新登录。
     *
     * @param token 不带 Bearer 前缀的有效签名令牌；重复撤销同一会话保持幂等
     */
    void logout(String token);

    /**
     * 只允许当前有效会话软注销自己的账号，并撤销 PC/H5/PLUGIN 的全部登录状态。
     *
     * @param token 不带 Bearer 前缀的当前访问令牌，不允许已被替换的旧令牌注销账号
     */
    void deregister(String token);
}
