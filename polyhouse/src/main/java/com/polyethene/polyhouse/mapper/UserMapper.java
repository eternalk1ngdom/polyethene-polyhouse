package com.polyethene.polyhouse.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.polyethene.polyhouse.model.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * 使用 MyBatis-Plus 的绑定参数 CRUD 访问 sys_user，并复用实体的角色、JSON 和逻辑删除映射。
 * 账号判重、密码验证与权限决策属于后续 Service，不放入持久层。
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
