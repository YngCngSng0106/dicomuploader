package com.zlyw.handler;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "upload.dicompath=target/not-exists-for-test")
class DicomUploaderApplicationTests {

    @Test
    void contextLoads() {
    }

}
