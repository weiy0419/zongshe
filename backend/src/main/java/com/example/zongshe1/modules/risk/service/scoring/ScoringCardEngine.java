package com.example.zongshe1.modules.risk.service.scoring;

import com.example.zongshe1.modules.loanapplication.entity.LoanApplication;
import com.example.zongshe1.modules.user.entity.User;

/**
 * 评分卡计分引擎：从业务对象抽取特征并汇总为卡分数（分数越高表示越优质，风险越低）。
 * 后续可替换为从数据库加载分箱/权重后的实现。
 */
public interface ScoringCardEngine {

    ScoringCardOutcome evaluate(User user, LoanApplication application);
}
