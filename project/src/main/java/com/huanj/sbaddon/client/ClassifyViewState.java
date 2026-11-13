package com.huanj.sbaddon.client;

/**
 * 分类视图的全局开关（客户端单界面，静态即可）。
 * <p>
 * 分类视图接管精妙背包的槽位排版后，SB 自带的滚动面板不该再滚动、也不该显示滚动条，
 * 但**不能把它从控件列表里摘掉** —— 物品正是由它的 {@code drawPanel} 调
 * {@code screen.renderInventorySlots} 画出来的，摘掉物品就全没了。
 * 所以改为：保留面板，仅通过本开关让它「认为内容不需要滚动」并拒绝鼠标交互。
 */
public final class ClassifyViewState {

    /** 分类视图是否正在接管槽位排版。 */
    public static boolean lockScrollPanel;

    private ClassifyViewState() {
    }
}
