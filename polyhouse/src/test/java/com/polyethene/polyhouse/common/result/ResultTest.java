package com.polyethene.polyhouse.common.result;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.model.vo.ValidationProbeVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 校验统一响应信封的三字段 JSON 契约、失败码正数边界与角色语义编码。 */
class ResultTest {

    @ParameterizedTest(name = "失败响应拒绝非正数业务码：{0}")
    @ValueSource(ints = {Result.SUCCESS_CODE, -1})
    void shouldRejectNonPositiveCodeForFailureResult(int invalidCode) {
        assertThrows(IllegalArgumentException.class, () -> Result.failure(invalidCode, "invalid"));
    }

    @Test
    void shouldSerializeOnlyContractFieldsAndSemanticRole() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode json = mapper.readTree(mapper.writeValueAsString(
                Result.success(new ValidationProbeVO(UserRoleEnum.OWNER))));

        assertEquals(3, json.size());
        assertEquals(0, json.get("code").intValue());
        assertEquals("操作成功", json.get("message").textValue());
        assertEquals(1, json.get("data").size());
        assertEquals("OWNER", json.get("data").get("role").textValue());
    }

    @Test
    void shouldKeepExplicitNullDataInFailureJson() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode json = mapper.readTree(mapper.writeValueAsString(Result.failure(ErrorCode.UNAUTHORIZED)));

        assertEquals(3, json.size());
        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), json.get("code").intValue());
        assertTrue(json.get("data").isNull());
    }
}
