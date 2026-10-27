package com.polyethene.polyhouse.ops;

import com.polyethene.polyhouse.common.exception.BusinessException;
import com.polyethene.polyhouse.mapper.OwnerBootstrapMapper;
import com.polyethene.polyhouse.service.AuthRedisStore;
import com.polyethene.polyhouse.service.OwnerBootstrapService;
import org.mybatis.spring.annotation.MapperScan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;

/** 独立运维入口；只装配存储及初始化服务，既不启动 Web 端口，也不扫描 Controller。 */
public final class OwnerBootstrapCommand {
    private static final Logger LOG = LoggerFactory.getLogger(OwnerBootstrapCommand.class);

    private OwnerBootstrapCommand() {
    }

    /** 进程退出码由检查/事务结果决定，Shell 不会把仅成功启动 Spring 当作授权完成。 */
    public static void main(String[] args) {
        System.exit(execute(args));
    }

    /** 先严格解析受控参数再启动上下文；调用者不能借运维参数传入任意 Spring 配置。 */
    static int execute(String[] args) {
        try {
            Arguments request = parse(args);
            try (var context = new SpringApplicationBuilder(OperationsConfiguration.class)
                    .profiles("owner-bootstrap").web(WebApplicationType.NONE)
                    .bannerMode(Banner.Mode.OFF).logStartupInfo(false)
                    .run("--spring.main.web-application-type=none")) {
                OwnerBootstrapService service = context.getBean(OwnerBootstrapService.class);
                if (!request.apply()) {
                    var target = service.inspect(request.userId(), request.username());
                    LOG.info("只读检查通过：userId={}，username={}，当前角色={}；未修改角色、审计或会话。正式执行须加 -Apply。",
                            target.id(), target.username(), target.role());
                } else {
                    boolean first = service.initialize(request.userId(), request.username(), System.getProperty("user.name"));
                    LOG.info("屋主初始化已提交：userId={}，username={}，首次确认={}，旧会话已撤销，请重新登录。",
                            request.userId(), request.username(), first);
                }
            }
            return 0;
        } catch (BusinessException exception) {
            LOG.error("{}", exception.getMessage());
        } catch (IllegalArgumentException exception) {
            LOG.error("参数不合法；请使用 Initialize-Owner.ps1 -UserId <正整数编号> -Username <规范小写账号> [-Apply]");
        } catch (RuntimeException exception) {
            // 禁止输出原始异常或异常链，连接配置和底层 SQL 可能含运维敏感信息。
            LOG.error("运维命令未完成，请核对私有配置和存储连接后重新检查；不要在聊天中提供凭据。");
        }
        return 2;
    }

    /** 参数白名单兼顾 Java 直接运行场景；重复键、未知键和多余位置参数全部拒绝。 */
    static Arguments parse(String[] args) {
        String id = null;
        String username = null;
        boolean apply = false;
        for (String arg : args) {
            if (arg.startsWith("--user-id=") && id == null) id = arg.substring("--user-id=".length());
            else if (arg.startsWith("--username=") && username == null) username = arg.substring("--username=".length());
            else if (arg.equals("--apply") && !apply) apply = true;
            else throw new IllegalArgumentException();
        }
        if (id == null || !id.matches("[1-9][0-9]{0,18}") || username == null
                || !username.matches("[a-z0-9_]{4,32}")) throw new IllegalArgumentException();
        return new Arguments(Long.parseLong(id), username, apply);
    }

    /** 运维参数不包含密码或令牌；只有显式 apply 才允许提交。 */
    record Arguments(long userId, String username, boolean apply) {
    }

    /** 独立入口显式启用此配置；普通启动的组件扫描因 profile 不符而跳过。 */
    @Configuration(proxyBeanMethods = false)
    @Profile("owner-bootstrap")
    @EnableAutoConfiguration
    @MapperScan(basePackageClasses = OwnerBootstrapMapper.class)
    @Import({OwnerBootstrapService.class, AuthRedisStore.class})
    static class OperationsConfiguration {
    }
}
