package com.zlyw.utils;

import java.util.HashMap;
import java.util.Properties;

import org.dcm4che3.data.UID;
import org.dcm4che3.net.pdu.CommonExtendedNegotiation;
import org.dcm4che3.util.StringUtils;
public class RelatedGeneralSOPClasses {


    private final HashMap<String,CommonExtendedNegotiation> commonExtNegs =
            new HashMap<String,CommonExtendedNegotiation>();

    public void init(Properties props) {
        for (String cuid : props.stringPropertyNames())
            commonExtNegs.put(cuid, new CommonExtendedNegotiation(cuid,
                    UID.Storage,
                    StringUtils.split(props.getProperty(cuid), ',')));
    }

    public CommonExtendedNegotiation getCommonExtendedNegotiation(String cuid) {
        CommonExtendedNegotiation commonExtNeg = commonExtNegs.get(cuid);
        return commonExtNeg != null
                ? commonExtNeg
                : new CommonExtendedNegotiation(cuid, UID.Storage);
    }
}
