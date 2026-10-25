package com.polyethene.polyhouse.common.context;

import com.polyethene.polyhouse.common.constant.UserRoleEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserContextTest {

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    void shouldStoreAndClearAuthenticatedIdentity() {
        UserContext.set(42L, UserRoleEnum.INSIDER);

        assertTrue(UserContext.isAuthenticated());
        assertEquals(42L, UserContext.getUserId());
        assertEquals(UserRoleEnum.INSIDER, UserContext.getRole());

        UserContext.clear();

        assertFalse(UserContext.isAuthenticated());
        assertNull(UserContext.getUserId());
        assertNull(UserContext.getRole());
    }

    @Test
    void shouldRejectIncompleteIdentity() {
        assertThrows(IllegalArgumentException.class, () -> UserContext.set(0L, UserRoleEnum.USER));
        assertThrows(NullPointerException.class, () -> UserContext.set(42L, null));
        assertFalse(UserContext.isAuthenticated());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldIsolateSimultaneousIdentitiesAcrossThreads(boolean virtualThreads) throws Exception {
        UserContext.set(42L, UserRoleEnum.OWNER);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService executor = virtualThreads
                ? Executors.newVirtualThreadPerTaskExecutor() : Executors.newFixedThreadPool(2)) {
            try {
                Future<Long> first = executor.submit(() -> isolatedIdentity(100L, UserRoleEnum.USER, ready, release));
                Future<Long> second = executor.submit(() -> isolatedIdentity(200L, UserRoleEnum.INSIDER, ready, release));
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                release.countDown();

                assertEquals(100L, first.get(5, TimeUnit.SECONDS));
                assertEquals(200L, second.get(5, TimeUnit.SECONDS));
                assertEquals(42L, UserContext.getUserId());
                assertEquals(UserRoleEnum.OWNER, UserContext.getRole());
            } finally {
                // 即使断言失败也要放行任务，避免执行器关闭时等待被测试线程造成挂起。
                release.countDown();
            }
        }
    }

    @Test
    void shouldNotLeakIdentityWhenWorkerThreadIsReused() throws Exception {
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> {
                try {
                    UserContext.set(100L, UserRoleEnum.USER);
                    assertTrue(UserContext.isAuthenticated());
                } finally {
                    UserContext.clear();
                }
            }).get(5, TimeUnit.SECONDS);

            executor.submit(() -> {
                assertFalse(UserContext.isAuthenticated());
                assertNull(UserContext.getUserId());
                assertNull(UserContext.getRole());
            }).get(5, TimeUnit.SECONDS);
        }
    }

    private Long isolatedIdentity(long userId, UserRoleEnum role, CountDownLatch ready,
                                  CountDownLatch release) throws InterruptedException {
        try {
            assertFalse(UserContext.isAuthenticated());
            UserContext.set(userId, role);
            ready.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            assertEquals(role, UserContext.getRole());
            return UserContext.getUserId();
        } finally {
            UserContext.clear();
        }
    }
}
