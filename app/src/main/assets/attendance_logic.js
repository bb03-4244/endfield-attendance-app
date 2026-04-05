function getSignV2(path, ts, secret) {
    // 서버가 요구하는 정확한 JSON 구조 (순서와 타입이 중요함)
    var bodyObj = {
        platform: "3",
        timestamp: parseInt(ts),
        dId: "",
        vName: "1.0.0"
    };
    var bodyStr = JSON.stringify(bodyObj);
    var combined = path + ts + bodyStr;

    // Kotlin 브릿지(Android 객체)를 통해 해싱 수행
    var hmacHex = Android.hmacSha256(combined, secret);
    return Android.md5(hmacHex);
}