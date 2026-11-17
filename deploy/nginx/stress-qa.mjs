// 第十周作业题1：网关限流压测脚本（node stress-qa.mjs，无第三方依赖）
// 100 个并发 POST 打到 Nginx(:8090) 的 /api/v1/qa，统计放行/被限流(503)数量。
// 预期（rate=10r/s + burst=20 nodelay）：放行约 30，拒绝约 70 —— 数字直观展示网关削峰。
import http from 'node:http';

const N = 100;
let pass = 0, limited = 0, other = 0;
const body = JSON.stringify({
  sessionId: 'stress-' + Date.now(),
  question: '压力测试：景区开放时间',
  interactionType: 'TEXT',
});

await Promise.all(Array.from({ length: N }, () => new Promise((resolve) => {
  const req = http.request({
    host: 'localhost', port: 8090, path: '/api/v1/qa', method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) },
  }, (res) => {
    res.resume();
    res.on('end', () => {
      if (res.statusCode === 200) pass++;
      else if (res.statusCode === 503) limited++;
      else other++;
      resolve();
    });
  });
  req.on('error', () => { other++; resolve(); });
  req.write(body);
  req.end();
})));

console.log(`total=${N}  放行(200)=${pass}  被限流(503)=${limited}  其他=${other}`);
