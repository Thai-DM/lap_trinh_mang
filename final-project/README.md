# Networked Super Smash Profs ? source modules

H??ng d?n ??y ?? v? ki?n tr?c, c?ng ngh?, c?i ??t, c?u h?nh, database, ch?y server/client/web, ki?m th?, k?t qu? v? gi?i h?n n?m t?i **[README ? g?c repository](../README.md)**.

Th? m?c n?y ch?a c?c Gradle module `common`, `server`, `core`, `desktop`, `tests`; `web` v? `teavm-probe` l? Gradle project ri?ng. Y?u c?u **JDK 17**. L?n build ??u c?n Internet:

```powershell
.\gradlew.bat :server:installCluster :desktop:dist --no-daemon '-Dorg.gradle.jvmargs=-Xmx512m'
```

Linux/macOS: d?ng `bash ./gradlew` thay `.\gradlew.bat`.

- Ch?y server/client v? c?u h?nh LAN: [h??ng d?n ch?nh](../README.md#ch?y-server-v?-client-desktop).
- Web: [h??ng d?n web](web/README.md).
- C?m worker: [h??ng d?n cluster](../docs/scalability/README.md).
- Y?u c?u h?c ph?n: [Instruction](Instruction.md), [Topics](Topics.md), [Submission](Submission.md).
- T?c gi? v? t?i li?u game local g?c: [README_UPSTREAM.md](README_UPSTREAM.md). ??y l? ghi nh?n ngu?n k? th?a; c?c l?nh trong t?i li?u g?c kh?ng thay th? h??ng d?n ch?y b?n m?ng hi?n t?i.
