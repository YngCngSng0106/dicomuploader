package com.zlyw.handler;

import com.zlyw.utils.StoreSCU;
import lombok.extern.slf4j.Slf4j;
import org.dcm4che3.net.ApplicationEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.File;

@Component
@Slf4j
public class Uploader implements ApplicationRunner {

    @Value("${server.ip}")
    private String pacsIpString;

    @Value("${server.port}")
    private String pacsPortString;

    @Value("${upload.dicompath}")
    private String uploadPath;

    public void upload() {
        log.trace("dicompath:" + this.uploadPath);
        log.trace("pacsIp:" + this.pacsIpString);
        log.trace("pacsPort:" + this.pacsPortString);
        this.uploadDicom();
    }

    private void uploadDicom() {
        File dicomPathFolder = new File(this.uploadPath);
        if (!dicomPathFolder.exists() || !dicomPathFolder.isDirectory()) {
            log.warn("上传目录不存在或不是目录: {}", this.uploadPath);
            return;
        }
        try {
            StoreSCU storescu = new StoreSCU(new ApplicationEntity("STORESCU"));
            storescu.uploadStreaming("Limage", pacsIpString,
                    Integer.parseInt(pacsPortString), dicomPathFolder);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        this.upload();
    }
}
