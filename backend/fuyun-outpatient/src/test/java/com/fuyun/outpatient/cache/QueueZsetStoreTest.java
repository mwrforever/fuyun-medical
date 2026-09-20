package com.fuyun.outpatient.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuyun.outpatient.entity.QueueTicket;
import com.fuyun.outpatient.enums.TicketStatus;
import com.fuyun.outpatient.enums.TicketType;
import com.fuyun.outpatient.mapper.QueueTicketMapper;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 候诊队列 Redis ZSET 存储单测（M03 候诊叫号加速视图，Task 7 + fix round 1）：键命名（fy:
 * outpatient:queue:{deptCode}，A.5-1+hash tag）、惰性重建三态（键在位 -1 零写/键缺失整体 ZADD+TTL
 * ——Spec :210 重启恢复权威面）、原子出队扫描（可叫态词表 WAITING/PASSED+医生匹配谓词：未指派或
 * 指派一致；失效成员清理；Lua 守卫败者继续扫描）、快照解析与移除透传。Lua 脚本本体语义由真栈 IT
 * 验证，本单测锚定门面契约。
 */
@ExtendWith(MockitoExtension.class)
class QueueZsetStoreTest {

    /** 队列键（与主类键命名同源：{deptCode} 兼作 Redis Cluster hash tag） */
    private static final String QUEUE_KEY = "fy:outpatient:queue:{DEP001}";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ZSetOperations<String, String> zsetOperations;

    @Mock
    private QueueTicketMapper queueTicketMapper;

    private QueueZsetStore store;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForZSet()).thenReturn(zsetOperations);
        store = new QueueZsetStore(redisTemplate, queueTicketMapper);
    }

    /**
     * 票据行替身。
     *
     * @param id       票据主键
     * @param status   票据状态
     * @param doctorId 指派医生（null=未指派）
     * @return 票据替身，非空
     */
    private QueueTicket ticketOf(long id, TicketStatus status, String doctorId) {
        QueueTicket ticket = new QueueTicket();
        ticket.setId(id);
        ticket.setDoctorId(doctorId);
        ticket.setTicketType(TicketType.FIRST);
        ticket.setStatus(status);
        return ticket;
    }

    @Test
    @DisplayName("enqueue：ZADD member=pk+编码分，并续期队列键 TTL（禁无 TTL 键）")
    void enqueueAddsMemberWithScoreAndTtl() {
        store.enqueue("DEP001", 501L, 100_000_000_001L);

        verify(zsetOperations).add(QUEUE_KEY, "501", 100_000_000_001L);
        verify(redisTemplate).expire(eq(QUEUE_KEY), any());
    }

    @Test
    @DisplayName("pollTop：首个未指派 WAITING 票匹配——Lua 守卫出队成功返回 ticketPk")
    void pollTopReturnsFirstUnassignedTicketAfterLuaGuard() {
        Set<String> members = new LinkedHashSet<>(List.of("501", "502"));
        when(zsetOperations.range(QUEUE_KEY, 0, -1)).thenReturn(members);
        when(queueTicketMapper.selectById(501L)).thenReturn(ticketOf(501L, TicketStatus.WAITING, null));
        when(redisTemplate.execute(any(), anyList(), anyString())).thenReturn(1L);

        Long polled = store.pollTop("DEP001", "DOC001");

        assertThat(polled).isEqualTo(501L);
        verify(redisTemplate).execute(any(), eq(List.of(QUEUE_KEY)), eq("501"));
    }

    @Test
    @DisplayName("pollTop：PASSED 过号再入票属可叫态——不跳过、正常出队（fix round 1 Important-2）")
    void pollTopTreatsPassedTicketAsQueueable() {
        Set<String> members = new LinkedHashSet<>(List.of("501"));
        when(zsetOperations.range(QUEUE_KEY, 0, -1)).thenReturn(members);
        when(queueTicketMapper.selectById(501L)).thenReturn(ticketOf(501L, TicketStatus.PASSED, null));
        when(redisTemplate.execute(any(), anyList(), anyString())).thenReturn(1L);

        assertThat(store.pollTop("DEP001", "DOC001")).isEqualTo(501L);
    }

    @Test
    @DisplayName("pollTop：失效成员（已 CALLED 遗留态）原子清理后继续扫描下一位可叫票")
    void pollTopCleansUpStaleMemberAndSkipsToNextQueueable() {
        Set<String> members = new LinkedHashSet<>(List.of("501", "502"));
        when(zsetOperations.range(QUEUE_KEY, 0, -1)).thenReturn(members);
        // 首票已 CALLED（重呼后遗留成员）——清理移除后继续；次票 WAITING 未指派——出队成功
        when(queueTicketMapper.selectById(501L)).thenReturn(ticketOf(501L, TicketStatus.CALLED, null));
        when(queueTicketMapper.selectById(502L)).thenReturn(ticketOf(502L, TicketStatus.WAITING, null));
        when(redisTemplate.execute(any(), anyList(), eq("501"))).thenReturn(1L);
        when(redisTemplate.execute(any(), anyList(), eq("502"))).thenReturn(1L);

        Long polled = store.pollTop("DEP001", "DOC001");

        assertThat(polled).isEqualTo(502L);
        // 失效成员清理：对 501 执行过一次 Lua 原子移除
        verify(redisTemplate).execute(any(), eq(List.of(QUEUE_KEY)), eq("501"));
    }

    @Test
    @DisplayName("pollTop：指派不一致票跳过（保留给指派医生）+Lua 败者（0）继续扫描——最终无可叫返回 null")
    void pollTopSkipsAssignedMismatchAndLuaLoserReturningNull() {
        Set<String> members = new LinkedHashSet<>(List.of("501", "502"));
        when(zsetOperations.range(QUEUE_KEY, 0, -1)).thenReturn(members);
        // 首票指派 DOC002≠DOC001 跳过；次票未指派但 Lua 返回 0（并发被夺）继续；无后续→null
        when(queueTicketMapper.selectById(501L)).thenReturn(ticketOf(501L, TicketStatus.WAITING, "DOC002"));
        when(queueTicketMapper.selectById(502L)).thenReturn(ticketOf(502L, TicketStatus.WAITING, null));
        when(redisTemplate.execute(any(), anyList(), anyString())).thenReturn(0L);

        assertThat(store.pollTop("DEP001", "DOC001")).isNull();
    }

    @Test
    @DisplayName("pollTop：空队列/成员全缺失——null 返回且零脚本执行")
    void pollTopReturnsNullWhenQueueEmpty() {
        when(zsetOperations.range(QUEUE_KEY, 0, -1)).thenReturn(null);

        assertThat(store.pollTop("DEP001", "DOC001")).isNull();
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), anyString());
    }

    @Test
    @DisplayName("remove：ZREM 透传（跨队列转接放旧票）")
    void removeDelegatesZrem() {
        store.remove("DEP001", 501L);

        verify(zsetOperations).remove(QUEUE_KEY, "501");
    }

    @Test
    @DisplayName("snapshot：ZRANGE 前 N 解析为 ticketPk 列表（score 升序）")
    void snapshotParsesMembersToTicketIds() {
        when(zsetOperations.range(QUEUE_KEY, 0, 1L)).thenReturn(new LinkedHashSet<>(List.of("501", "502")));

        assertThat(store.snapshot("DEP001", 2)).containsExactly(501L, 502L);
    }

    @Test
    @DisplayName("snapshot：空队列——空列表返回（零解析）")
    void snapshotReturnsEmptyListWhenQueueEmpty() {
        when(zsetOperations.range(QUEUE_KEY, 0, 1L)).thenReturn(null);

        assertThat(store.snapshot("DEP001", 2)).isEmpty();
    }

    @Test
    @DisplayName("rebuildIfMissing：键在位返回 -1 零写（幂等——禁回灌非在队票）")
    void rebuildSkipsWhenKeyPresent() {
        when(redisTemplate.hasKey(QUEUE_KEY)).thenReturn(true);

        assertThat(store.rebuildIfMissing("DEP001", Map.of(501L, 100_000_000_001L)))
                .isEqualTo(-1);
        verify(zsetOperations, never()).add(anyString(), anyString(), anyDouble());
        verify(redisTemplate, never()).expire(eq(QUEUE_KEY), any());
    }

    @Test
    @DisplayName("rebuildIfMissing：键缺失——待重叫权威行整体 ZADD 重建+TTL，返回重建票数（Spec :210）")
    void rebuildAddsAllWaitingTicketsWhenKeyMissing() {
        when(redisTemplate.hasKey(QUEUE_KEY)).thenReturn(false);

        int rebuilt = store.rebuildIfMissing("DEP001", Map.of(501L, 100_000_000_001L, 502L, 100_000_000_002L));

        assertThat(rebuilt).isEqualTo(2);
        verify(zsetOperations).add(QUEUE_KEY, "501", 100_000_000_001L);
        verify(zsetOperations).add(QUEUE_KEY, "502", 100_000_000_002L);
        verify(redisTemplate).expire(eq(QUEUE_KEY), any());
    }
}
