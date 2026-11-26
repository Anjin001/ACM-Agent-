package cn.xunlian;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;
import static cn.xunlian.Models.*;

@RestController
@RequestMapping("/api/learning")
public class LearningController {
    private final CoachStore store;
    private final CoachController coach;
    private final List<Question> questions;

    public LearningController(CoachStore store, CoachController coach, ObjectMapper json) throws Exception {
        this.store = store; this.coach = coach;
        questions = json.readValue(new ClassPathResource("questions.json").getInputStream(), new TypeReference<>() {});
    }

    @GetMapping
    public Map<String,Object> catalog(HttpSession session) {
        userId(session);
        List<Map<String,Object>> publicQuestions = questions.stream().map(question -> Map.<String,Object>of(
                "id",question.id(),"route",question.route(),"topic",question.topic(),"prompt",question.prompt(),"options",question.options(),"hintCount",question.hints().size())).toList();
        return Map.of("questions",publicQuestions,"challenges",List.of(
                Map.of("id","binary","title","二分边界检查","topic","二分","description","程序可能遗漏最后一个候选位置。输入有序数组和目标值，构造反例。","code","while (left < right) {\n  int mid = (left + right) / 2;\n  if (a[mid] == target) return true;\n  if (a[mid] < target) left = mid + 1;\n  else right = mid - 1;\n}\nreturn false;","fixes",List.of("找到目标后继续缩小区间","使用 left <= right 并维护闭区间更新","始终把 left 设为 0")),
                Map.of("id","overflow","title","整数累加的范围","topic","复杂度分析","description","预设程序用 32 位有符号整数累加。构造一个总和超出范围的数组。","code","int total = 0;\nfor (int x : a) total += x;\nreturn total;","fixes",List.of("使用 64 位整数进行累加","计算后把结果转成字符串","把数组倒序后继续用 int 累加")),
                Map.of("id","average","title","平均值为什么变小了","topic","模拟","description","预设程序先做整数除法再返回结果。构造一个平均值带小数的数组。","code","long long total = 0;\nfor (int x : a) total += x;\nreturn total / a.size();","fixes",List.of("先排序再求平均","对最后结果取绝对值","在除法前将 total 转换为浮点数"))));
    }

    @PostMapping("/hint")
    public Map<String,Object> hint(@RequestBody HintRequest body, HttpSession session) {
        long id = userId(session);
        Snapshot snapshot = checked(id,body.revision());
        Question question = questions.stream().filter(item -> item.id().equals(body.questionId())).findFirst().orElseThrow(() -> bad("诊断题不存在"));
        int level = Math.min(question.hints().size(),snapshot.workspace().learningHints.getOrDefault(question.id(),0)+1);
        snapshot.workspace().learningHints.put(question.id(),level);
        store.save(id,snapshot.revision(),snapshot.workspace());
        return Map.of("state",coach.state(session),"hint",question.hints().get(level-1),"level",level);
    }

    @PostMapping("/assessment")
    public Map<String,Object> assess(@RequestBody AssessmentRequest body, HttpSession session) {
        List<Question> selected = questions.stream().filter(question -> question.route().equals(body.route())).toList();
        if(selected.isEmpty() || body.answers()==null || body.answers().size()!=selected.size())throw bad("请完成当前路线的全部理解题");
        long id=userId(session);
        Snapshot snapshot=checked(id,body.revision());
        List<AssessmentItem> items=new ArrayList<>();
        Set<String> suggested=new LinkedHashSet<>();
        for(Question question:selected) {
            Integer chosen=body.answers().get(question.id());
            if(chosen==null || chosen<0 || chosen>=question.options().size())throw bad("请为每道题选择有效答案");
            boolean correct=chosen==question.answer();
            int hints=snapshot.workspace().learningHints.getOrDefault(question.id(),0);
            items.add(new AssessmentItem(question.id(),question.topic(),chosen,question.answer(),correct,hints,question.explanation()));
            if(!correct || hints>0)suggested.add(question.topic());
        }
        if(suggested.isEmpty())selected.forEach(question->suggested.add(question.topic()));
        Assessment result=new Assessment(UUID.randomUUID().toString(),body.route(),(int)items.stream().filter(AssessmentItem::correct).count(),items.size(),Instant.now().toString(),items,new ArrayList<>(suggested));
        snapshot.workspace().assessments.add(result);
        selected.forEach(question->snapshot.workspace().learningHints.remove(question.id()));
        store.save(id,snapshot.revision(),snapshot.workspace());
        return Map.of("state",coach.state(session),"result",result);
    }

    @PostMapping("/challenge")
    public Map<String,Object> challenge(@RequestBody ChallengeRequest body,HttpSession session) {
        if(body.challengeId()==null || !Set.of("binary","overflow","average").contains(body.challengeId()))throw bad("挑战不存在");
        if(body.numbers()==null || body.numbers().isEmpty() || body.numbers().size()>30 || body.numbers().stream().anyMatch(value->value==null || value < -1000000000 || value>1000000000))throw bad("请输入 1～30 个范围在正负十亿以内的整数");
        if(body.fix()<0 || body.fix()>2 || body.explanation()==null || body.explanation().isBlank() || body.explanation().length()>1500)throw bad("请选择修复方式并填写 1～1500 字的解释");
        long id=userId(session);
        Snapshot snapshot=checked(id,body.revision());
        String buggy;
        String reference;
        int correctFix;
        if(body.challengeId().equals("binary")) {
            if(body.target()==null)throw bad("请填写二分查找目标");
            for(int i=1;i<body.numbers().size();i++)if(body.numbers().get(i)<body.numbers().get(i-1))throw bad("二分挑战要求数组按非递减顺序排列");
            int left=0,right=body.numbers().size()-1;
            boolean found=false;
            while(left<right) {
                int middle=(left+right)/2;
                int value=body.numbers().get(middle);
                if(value==body.target()){found=true;break;}
                if(value<body.target())left=middle+1;else right=middle-1;
            }
            buggy=String.valueOf(found);
            reference=String.valueOf(body.numbers().contains(body.target()));
            correctFix=1;
        }else {
            long total=body.numbers().stream().mapToLong(Integer::longValue).sum();
            if(body.challengeId().equals("overflow")) {
                buggy=String.valueOf((int)total);
                reference=String.valueOf(total);
                correctFix=0;
            }else {
                buggy=String.valueOf((double)(total/body.numbers().size()));
                reference=String.valueOf((double)total/body.numbers().size());
                correctFix=2;
            }
        }
        ChallengeResult result=new ChallengeResult(UUID.randomUUID().toString(),body.challengeId(),List.copyOf(body.numbers()),body.target(),buggy,reference,!buggy.equals(reference),body.fix()==correctFix,body.explanation().trim(),Instant.now().toString());
        snapshot.workspace().challenges.add(result);
        store.save(id,snapshot.revision(),snapshot.workspace());
        return Map.of("state",coach.state(session),"result",result);
    }

    private long userId(HttpSession session) {
        Object id=session.getAttribute("userId");
        if(!(id instanceof Long))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"请先登录");
        return (Long)id;
    }
    private Snapshot checked(long userId,long revision) {
        Snapshot snapshot=store.snapshot(userId);
        if(snapshot.revision()!=revision)throw new ResponseStatusException(HttpStatus.CONFLICT,"数据已有更新，请刷新后重试");
        return snapshot;
    }
    private ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
}
