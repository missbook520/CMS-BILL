package com.cms.bill.audit;

/**
 * 事件审计处置结果（参考 Avaya ECHI 落盘语义：一切事件含重复均留痕）。
 */
public enum EventAuditDisposition {

    /** 已收到（处理前先行落库；进程崩溃时该行保留，可据此对账补推） */
    RECEIVED,

    /** 已接受并参与统计 */
    ACCEPTED,

    /** 重复事件（事件ID幂等命中，无统计变更） */
    DUPLICATE,

    /** 已忽略（如序列号过旧/回绕距离非法、乱序、同状态转移），已确认但无统计变更 */
    IGNORED,

    /** 已拒绝（校验失败，如事件类型无法识别、时钟偏差超限），调用方需修正后重推 */
    REJECTED
}
