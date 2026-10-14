package com.example.zongshe1.modules.ops.cs;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class FaqCatalog {

    private final List<FaqEntry> entries = List.of(
            entry("repay-how", "怎么还款",
                    "打开借款端的还款页，选择本期应还并确认支付。支付成功后计划状态会更新。",
                    "怎么还款", "如何还款", "还款"),
            entry("repay-fail", "还款失败怎么办",
                    "还款失败时金额不会入账。请稍后重试，或确认支付账户余额后再还。",
                    "还款失败", "扣款失败", "还失败"),
            entry("overdue-meaning", "什么是逾期",
                    "到期未还清当期款项即视为逾期。逾期记录会影响后续借款审批。",
                    "什么是逾期", "逾期是什么", "怎么算逾期"),
            entry("overdue-effect", "逾期了怎么办",
                    "请尽快补上逾期款项。逾期期间可能产生罚息，并进入后续提醒。",
                    "逾期了", "逾期怎么办", "罚息", "逾期后果"),
            entry("contract-where", "合同在哪里看",
                    "申请通过后会生成合同。请在借款端的合同页查看金额、期限和利率。",
                    "合同在哪", "哪里看合同", "查看合同", "合同"),
            entry("contract-effect", "合同什么时候生效",
                    "合同在你确认签署后生效。未通过审批的申请不会进入签署。",
                    "合同生效", "何时生效", "签署"),
            entry("limit-how", "额度是多少",
                    "可借额度以当前审批结果为准，不能超过产品的额度上限。页面展示的可用额度才是这次能借的金额。",
                    "额度是多少", "能借多少", "可用额度", "额度"),
            entry("limit-change", "额度为什么变了",
                    "额度会随审批和贷中重评变化。重评给出的是建议额度，不会直接改掉已发放的借据。",
                    "额度变了", "额度调整", "提额"),
            entry("login-fail", "登录不了怎么办",
                    "请核对手机号和密码。多次失败时稍后再试，不要把验证码告诉他人。",
                    "登录不了", "登不上", "无法登录", "登录"),
            entry("password-forgot", "忘记密码怎么办",
                    "在登录页使用手机验证码重新设置密码。设置完成后用新密码登录。",
                    "忘记密码", "重置密码", "找回密码")
    );

    public List<FaqEntry> entries() {
        return entries;
    }

    private static FaqEntry entry(String id, String question, String answer, String... keywords) {
        return new FaqEntry(id, question, answer, List.of(keywords));
    }
}
