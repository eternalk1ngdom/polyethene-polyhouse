package com.polyethene.polyhouse.model.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.polyethene.polyhouse.common.constant.UserRoleEnum;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 对应 sys_user 的内部持久化对象；接口必须显式转换为 VO，避免泄露凭据和管理字段。
 */
@TableName(value = "sys_user", autoResultMap = true)
public class User {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String username;

    @JsonIgnore
    private String passwordHash;

    private String nickname;

    private String avatar;

    /** 使用已有枚举的 @EnumValue 存取语义字符串，不能将权限权重写入 role 列。 */
    private UserRoleEnum role;

    private Integer status;

    private String lastLoginIp;

    private LocalDateTime lastLoginTime;

    /** 自动结果映射使 JSON 在查询时也经过处理器，保留嵌套设置而非返回原始 JSON 文本。 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> extJson;

    @TableLogic(value = "0", delval = "1")
    private Integer deleted;

    /** 由 MySQL 默认值维护，避免普通实体更新覆盖数据库审计时间。 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createTime;

    /** 由 MySQL ON UPDATE 维护，不将查询得到的旧时间再次写回。 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updateTime;

    /** 返回内部用户主键，供持久层和鉴权使用。 */
    public Long getId() {
        return id;
    }

    /** 接收查库主键；新记录未提供主键时由 MyBatis-Plus 分配雪花 ID。 */
    public void setId(Long id) {
        this.id = id;
    }

    /** 返回登录账号，供账号查找和资料转换使用。 */
    public String getUsername() {
        return username;
    }

    /** 保存已经过业务边界校验的登录账号。 */
    public void setUsername(String username) {
        this.username = username;
    }

    /** 仅向内部密码校验逻辑提供哈希，不能用于接口输出。 */
    public String getPasswordHash() {
        return passwordHash;
    }

    /** 仅接收哈希结果；明文密码应停留在请求处理边界。 */
    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    /** 返回面向界面展示的昵称。 */
    public String getNickname() {
        return nickname;
    }

    /** 保存展示昵称，与登录账号保持独立。 */
    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    /** 返回头像地址，供安全资料投影使用。 */
    public String getAvatar() {
        return avatar;
    }

    /** 接收已保存的头像地址；未指定时允许数据库默认值生效。 */
    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    /** 返回语义角色，供统一权限判断使用。 */
    public UserRoleEnum getRole() {
        return role;
    }

    /** 接收数据库或受控管理逻辑提供的角色，不能绑定客户端注册字段。 */
    public void setRole(UserRoleEnum role) {
        this.role = role;
    }

    /** 返回账号启停状态，供后续登录业务检查。 */
    public Integer getStatus() {
        return status;
    }

    /** 接收内部账号状态，不将该管理字段开放给注册请求。 */
    public void setStatus(Integer status) {
        this.status = status;
    }

    /** 返回内部登录审计地址，不能进入公开资料。 */
    public String getLastLoginIp() {
        return lastLoginIp;
    }

    /** 接收后续登录流程采集的地址，不记录到普通应用日志。 */
    public void setLastLoginIp(String lastLoginIp) {
        this.lastLoginIp = lastLoginIp;
    }

    /** 返回最近登录时间，供内部审计使用。 */
    public LocalDateTime getLastLoginTime() {
        return lastLoginTime;
    }

    /** 接收登录业务提供的审计时间。 */
    public void setLastLoginTime(LocalDateTime lastLoginTime) {
        this.lastLoginTime = lastLoginTime;
    }

    /** 返回内部弹性配置，不将其作为资料接口的无约束输出。 */
    public Map<String, Object> getExtJson() {
        return extJson;
    }

    /** 接收 JSON 对象设置，由类型处理器负责持久化。 */
    public void setExtJson(Map<String, Object> extJson) {
        this.extJson = extJson;
    }

    /** 返回内部软删除标记，由 MyBatis-Plus 查询过滤。 */
    public Integer getDeleted() {
        return deleted;
    }

    /** 接收数据库软删除状态，不允许客户端绑定。 */
    public void setDeleted(Integer deleted) {
        this.deleted = deleted;
    }

    /** 返回数据库生成的创建时间。 */
    public LocalDateTime getCreateTime() {
        return createTime;
    }

    /** 用于读取数据库时间，写入策略仍禁止实体覆盖审计列。 */
    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    /** 返回数据库维护的更新时间。 */
    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    /** 用于读取数据库时间，普通更新不会将旧时间写回。 */
    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
