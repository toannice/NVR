import * as fs from 'fs';
import * as path from 'path';

// Parse Playwright JSON output
const resultsPath = path.join(__dirname, '../test-results/results.json');

if (!fs.existsSync(resultsPath)) {
    console.error('No test results found. Please run tests first.');
    process.exit(1);
}

const rawData = fs.readFileSync(resultsPath, 'utf8');
const report = JSON.parse(rawData);

const totalTests = report.stats.expected;
const passedTests = report.stats.expected - report.stats.unexpected - report.stats.flaky;
const failedTests = report.stats.unexpected;

const dateStr = new Date().toISOString().split('T')[0];
const reportName = `Ket_qua_test_${dateStr}.md`;

let markdownContent = `# BÁO CÁO KIẾM THỬ NVR (Playwright E2E & Security)
**Ngày thực hiện:** ${dateStr}

## Tổng quan Kết quả
- **Tổng số kịch bản:** ${totalTests}
- **Đạt (Pass):** ✅ ${passedTests}
- **Lỗi (Fail):** ❌ ${failedTests}

## Chi tiết các lỗi hệ thống (Nếu có)
`;

if (failedTests > 0) {
    report.suites.forEach((suite: any) => {
        suite.specs.forEach((spec: any) => {
            if (!spec.ok) {
                markdownContent += `### ❌ Thất bại: ${spec.title}\n`;
                const errorParts = spec.tests[0].results[0].error;
                if (errorParts) {
                    markdownContent += "```\n" + errorParts.message + "\n```\n\n";
                }
                
                // Trích xuất stdOut/stdErr
                if (spec.tests[0].results[0].stdout.length > 0) {
                    markdownContent += `**Logs Console:**\n\`\`\`\n${spec.tests[0].results[0].stdout.map((s:any)=>s.text).join('\n')}\n\`\`\`\n`;
                }
            }
        });
    });
} else {
    markdownContent += "> 🎉 Hệ thống vượt qua toàn bộ các bài kiểm tra bảo mật và chức năng xuất sắc!\n";
}

markdownContent += `
## Khuyến nghị
- Đọc file báo cáo \`playwright-report/index.html\` để xem video/hình ảnh replay cho các case bị lỗi.
`;

fs.writeFileSync(path.join(__dirname, '..', reportName), markdownContent);
console.log(`✅ [Success] Đã tạo file báo cáo tự động: ${reportName}`);
