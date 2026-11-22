// 第十周作业题3：并行爆发脚本（node burst-qa.mjs [数量] [sessionId]，无第三方依赖）
// 并行 POST 直连后端 8080（绕过 Nginx 限流），几秒内把消息全部灌进 Kafka，
// 然后立刻杀进程 → lag 才能演出来（串行 curl 太慢，consumer 会同步消化完）。
// 用法：node burst-qa.mjs 60 demo-kill
import http from 'node:http';

const N = Number(process.argv[2] ?? 60);
const sessionId = process.argv[3] ?? ('burst-' + Date.now());
let sent = 0, ok = 0, fail = 0;

const body = JSON.stringify({
  sessionId,
  question: '晚上有灯光秀吗',
  interactionType: 'TEXT',
});

await Promise.all(Array.from({ length: N }, () => new Promise((resolve) => {
  const req = http.request({
    host: '127.0.0.1', port: 8080, path: '/api/v1/qa', method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) },
  }, (res) => {
    res.resume();
    res.on('end', () => { sent++; res.statusCode === 200 ? ok++ : fail++; resolve(); });
  });
  req.on('error', () => { sent++; fail++; resolve(); });
  req.write(body);
  req.end();
})));

console.log(`burst done: 总数=${N} 成功=${ok} 失败=${fail} sessionId=${sessionId}`);
