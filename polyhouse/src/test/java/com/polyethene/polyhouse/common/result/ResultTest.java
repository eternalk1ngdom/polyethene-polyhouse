package com.polyethene.polyhouse.common.result;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polyethene.polyhouse.common.constant.ErrorCode;
import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import com.polyethene.polyhouse.model.vo.ValidationProbeVO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResultTest {

    @Test
    void shouldCreateSuccessResultWithData() {
        Result<String> result = Result.success("payload");

        assertEquals(Result.SUCCESS_CODE, result.getCode());
        assertEquals("操作成功", result.getMessage());
        assertEquals("payload", result.getData());
    }

    @Test
    void shouldCreateFailureResultWithoutData() {
        Result<Void> result = Result.failure(40100, "未登录");

        assertEquals(40100, result.getCode());
        assertEquals("未登录", result.getMessage());
        assertNull(result.getData());
    }

    @Test
    void shouldRejectSuccessCodeForFailureResult() {
        assertThrows(IllegalArgumentException.class, () -> Result.failure(Result.SUCCESS_CODE, "invalid"));
        assertThrows(IllegalArgumentException.class, () -> Result.failure(-1, "invalid"));
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
