// Shizuku UserService 接口
// 这个接口的方法运行在 Shizuku 进程中,拥有 shell (uid 2000) 权限
// 用于绕过三星对 VpnService 的限制:不用 VPN,改用 shell 执行 settings + mihomo
package com.ys.proxy;

interface IWatchService {
    // 同步执行 shell 命令,返回合并的 stdout+stderr
    String exec(String cmd);

    // 后台启动 mihomo (nohup 脱离,UserService unbind 后仍运行)
    // binPath: /data/local/tmp/mihomo
    // homePath: /data/local/tmp/mihomo_home (存放 config.yaml)
    void startMihomo(String binPath, String homePath);

    // 停止 mihomo
    void stopMihomo();

    // 设置全局 http 代理: settings put global http_proxy host:port
    void setProxy(String proxy);

    // 清除全局 http 代理: settings delete global http_proxy
    void clearProxy();

    // 释放二进制: 从 srcPath cp 到 dstPath 并 chmod 755
    void installBinary(String srcPath, String dstPath);

    // 写入配置文件: 把 srcPath 的内容 cp 到 dstPath
    void installConfig(String srcPath, String dstPath);

    // 检查 mihomo 是否在运行
    boolean isMihomoRunning();
}
