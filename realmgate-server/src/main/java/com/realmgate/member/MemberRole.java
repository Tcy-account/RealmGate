package com.realmgate.member;

/**
 * 项目级成员角色（member.role）。
 * 对齐计划 §5.3 项目管理员 / §5.4 普通成员 / §5.5 Viewer。
 * 用 @Enumerated(STRING) 持久化为 "PM" / "MEMBER" / "VIEWER"，与前端下拉值一致。
 */
public enum MemberRole {
    PM,      // 项目管理员：管理项目成员、任务
    MEMBER,  // 普通成员：创建/修改自己的任务
    VIEWER   // 只能查看
}
