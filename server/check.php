<?php
/**
 * 码上取 · 应用内更新检查接口（部署到虚拟主机，如 https://msq.12.ink/api/check.php）
 *
 * 原理：服务端持有蒲公英 API Key，转发蒲公英 check 接口并转换为 App 端更新源格式。
 * 客户端零密钥；Key 永不出服务器。
 *
 * 部署步骤：
 * 1. 把本文件上传到虚拟主机 api/ 目录
 * 2. 同目录再上传 config.php（内容见 config.example.php，此文件不入 git 仓库）
 * 3. 访问 https://msq.12.ink/api/check.php 应返回 JSON（无新版本时 versionCode 为 0 或等于线上版本）
 *
 * versionCode 规则：取蒲公英「包上传时间」的 epoch 秒 ÷ 60，
 * 与 App 构建时 versionCode（构建时刻 epoch 毫秒 ÷ 60000）同一量纲，单调可比。
 */

declare(strict_types=1);

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');

// 加载密钥配置（与本文件同目录，不在网站可访问路径外也可，但建议目录禁止列目录/直接访问）
$configFile = __DIR__ . '/config.php';
if (!is_file($configFile)) {
    http_response_code(500);
    echo json_encode(['error' => 'config.php missing'], JSON_UNESCAPED_UNICODE);
    exit;
}
$config = require $configFile;
$apiKey = $config['pgyer_api_key'] ?? '';
$appKey = $config['pgyer_app_key'] ?? '';
if ($apiKey === '' || $appKey === '') {
    http_response_code(500);
    echo json_encode(['error' => 'keys not configured'], JSON_UNESCAPED_UNICODE);
    exit;
}

// 转发蒲公英 check 接口
$ch = curl_init('https://www.pgyer.com/apiv2/app/check');
curl_setopt_array($ch, [
    CURLOPT_POST           => true,
    CURLOPT_POSTFIELDS     => http_build_query(['_api_key' => $apiKey, 'appKey' => $appKey]),
    CURLOPT_RETURNTRANSFER => true,
    CURLOPT_TIMEOUT        => 10,
    CURLOPT_CONNECTTIMEOUT => 5,
]);
$body = curl_exec($ch);
$err  = curl_error($ch);
curl_close($ch);

if ($body === false || $body === '') {
    http_response_code(502);
    echo json_encode(['error' => 'pgyer unreachable: ' . $err], JSON_UNESCAPED_UNICODE);
    exit;
}

$pg = json_decode($body, true);
if (!is_array($pg) || ($pg['code'] ?? -1) !== 0 || !is_array($pg['data'] ?? null)) {
    http_response_code(502);
    echo json_encode(['error' => 'pgyer bad response', 'raw' => $pg], JSON_UNESCAPED_UNICODE);
    exit;
}

$d = $pg['data'];

// 无新版本时蒲公英返回 buildHaveNewVersion=false，但 App 端自行按 versionCode 比对更稳，
// 所以这里始终输出线上最新包信息；App 端 versionCode 大于本地才提示。
$out = [
    'versionCode' => isset($d['buildUpdated']) ? intdiv((int)$d['buildUpdated'], 60) : 0,
    'versionName' => (string)($d['buildVersion'] ?? ''),
    'apkUrl'      => (string)($d['downloadURL'] ?? ''),
    'changelog'   => (string)($d['buildUpdateDescription'] ?? ''),
];

echo json_encode($out, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
