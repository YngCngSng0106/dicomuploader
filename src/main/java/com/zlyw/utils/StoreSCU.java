package com.zlyw.utils;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.imageio.codec.Decompressor;
import org.dcm4che3.io.DicomInputStream;
import org.dcm4che3.io.DicomInputStream.IncludeBulkData;
import org.dcm4che3.io.SAXReader;
import org.dcm4che3.net.*;
import org.dcm4che3.net.pdu.AAssociateRQ;
import org.dcm4che3.net.pdu.PresentationContext;
import org.dcm4che3.net.pdu.RoleSelection;
import org.dcm4che3.tool.common.CLIUtils;
import org.dcm4che3.tool.common.DicomFiles;
import org.dcm4che3.util.SafeClose;
import org.dcm4che3.util.StringUtils;
import org.dcm4che3.util.TagUtils;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.*;
import java.security.GeneralSecurityException;
import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Slf4j
public class StoreSCU {

    public interface RSPHandlerFactory {

        DimseRSPHandler createDimseRSPHandler(File f);
    }

    private static ResourceBundle rb = ResourceBundle
            .getBundle("org.dcm4che3.tool.storescu.messages");

    private final ApplicationEntity ae;
    private final Connection remote;
    private final AAssociateRQ rq = new AAssociateRQ();
    private final RelatedGeneralSOPClasses relSOPClasses = new RelatedGeneralSOPClasses();
    private Attributes attrs;
    private String uidSuffix;
    private boolean relExtNeg;
    private int priority;
    private String tmpPrefix = "storescu-";
    private String tmpSuffix;
    private File tmpDir;
    private File tmpFile;
    private Association as;

    private long totalSize;
    private int filesScanned;
    private int filesSent;

    private RSPHandlerFactory rspHandlerFactory = new RSPHandlerFactory() {

        @Override
        public DimseRSPHandler createDimseRSPHandler(final File f) {

            return new DimseRSPHandler(as.nextMessageID()) {

                @Override
                public void onDimseRSP(Association as, Attributes cmd,
                                       Attributes data) {
                    super.onDimseRSP(as, cmd, data);
                    StoreSCU.this.onCStoreRSP(cmd, f);
                }
            };
        }
    };

    public StoreSCU(ApplicationEntity ae) throws IOException {
        this.remote = new Connection();
        this.ae = ae;
        rq.addPresentationContext(new PresentationContext(1,
                UID.Verification, UID.ImplicitVRLittleEndian));
    }

    public void setRspHandlerFactory(RSPHandlerFactory rspHandlerFactory) {
        this.rspHandlerFactory = rspHandlerFactory;
    }

    private static final String[] COMMON_STORAGE_SOP_CLASSES = {
            UID.CTImageStorage,
            UID.EnhancedCTImageStorage,
            UID.MRImageStorage,
            UID.EnhancedMRImageStorage,
            UID.UltrasoundImageStorage,
            UID.NuclearMedicineImageStorage,
            UID.PositronEmissionTomographyImageStorage,
            UID.SecondaryCaptureImageStorage,
            UID.XRayAngiographicImageStorage,
            UID.XRayRadiofluoroscopicImageStorage,
            UID.DigitalXRayImageStorageForPresentation,
            UID.DigitalXRayImageStorageForProcessing,
            UID.DigitalMammographyXRayImageStorageForPresentation,
            UID.DigitalMammographyXRayImageStorageForProcessing,
            UID.BreastTomosynthesisImageStorage,
            UID.BreastProjectionXRayImageStorageForPresentation,
            UID.BreastProjectionXRayImageStorageForProcessing,
            UID.OphthalmicTomographyImageStorage,
            UID.GrayscaleSoftcopyPresentationStateStorage,
            UID.SegmentationStorage,
            UID.SpatialRegistrationStorage,
            UID.RawDataStorage,
            UID.RTDoseStorage
    };

    private static final String[] COMMON_TRANSFER_SYNTAXES = {
            UID.ImplicitVRLittleEndian,
            UID.ExplicitVRLittleEndian,
            UID.JPEGBaseline8Bit,
            UID.JPEGExtended12Bit,
            UID.JPEGLossless,
            UID.JPEGLosslessSV1,
            UID.JPEGLSLossless,
            UID.JPEGLSNearLossless,
            UID.JPEG2000Lossless,
            UID.JPEG2000,
            UID.MPEG2MPML,
            UID.MPEG2MPHL,
            UID.MPEG4HP41,
            UID.MPEG4HP41BD,
            UID.HEVCMP51,
            UID.HEVCM10P51,
            UID.RLELossless,
            UID.DeflatedExplicitVRLittleEndian
    };

    public void preRegisterCommonStorageSOPClasses() {
        for (String cuid : COMMON_STORAGE_SOP_CLASSES)
            addOfferedStorageSOPClass(cuid, COMMON_TRANSFER_SYNTAXES);
    }

    public AAssociateRQ getAAssociateRQ() {
        return rq;
    }

    public Connection getRemoteConnection() {
        return remote;
    }

    public Attributes getAttributes() {
        return attrs;
    }

    public void setAttributes(Attributes attrs) {
        this.attrs = attrs;
    }

    public void setTmpFile(File tmpFile) {
        this.tmpFile = tmpFile;
    }

    public final void setPriority(int priority) {
        this.priority = priority;
    }

    public final void setUIDSuffix(String uidSuffix) {
        this.uidSuffix = uidSuffix;
    }

    public final void setTmpFilePrefix(String prefix) {
        this.tmpPrefix = prefix;
    }

    public final void setTmpFileSuffix(String suffix) {
        this.tmpSuffix = suffix;
    }

    public final void setTmpFileDirectory(File tmpDir) {
        this.tmpDir = tmpDir;
    }

    private static CommandLine parseComandLine(String[] args)
            throws ParseException {
        Options opts = new Options();
        CLIUtils.addConnectOption(opts);
        CLIUtils.addBindClientOption(opts, "STORESCU");
        CLIUtils.addAEOptions(opts);
        CLIUtils.addStoreTimeoutOption(opts);
        CLIUtils.addResponseTimeoutOption(opts);
        CLIUtils.addPriorityOption(opts);
        CLIUtils.addCommonOptions(opts);
        addStoreTCOptions(opts);
        addTmpFileOptions(opts);
        addRelatedSOPClassOptions(opts);
        addAttributesOption(opts);
        addUIDSuffixOption(opts);
        return CLIUtils.parseComandLine(args, opts, rb, StoreSCU.class);
    }

    private static void addAttributesOption(Options opts) {
        opts.addOption(Option.builder("s")
                .hasArgs()
                .argName("[seq.]attr=value")
                .desc(rb.getString("set"))
                .build());
    }

    public static void addUIDSuffixOption(Options opts) {
        opts.addOption(Option.builder().hasArg().argName("suffix")
                .desc(rb.getString("uid-suffix"))
                .longOpt("uid-suffix").build());
    }

    private static void addStoreTCOptions(Options opts) {
        opts.addOption(Option.builder()
                .hasArg()
                .argName("cuid:tsuid[(,|;)...]")
                .desc(rb.getString("store-tc"))
                .longOpt("store-tc")
                .build());
        opts.addOption(Option.builder()
                .hasArg()
                .argName("file|url")
                .desc(rb.getString("store-tcs"))
                .longOpt("store-tcs")
                .build());
    }

    public static void addTmpFileOptions(Options opts) {
        opts.addOption(Option.builder().hasArg().argName("directory")
                .desc(rb.getString("tmp-file-dir"))
                .longOpt("tmp-file-dir").build());
        opts.addOption(Option.builder().hasArg().argName("prefix")
                .desc(rb.getString("tmp-file-prefix"))
                .longOpt("tmp-file-prefix").build());
        opts.addOption(Option.builder().hasArg().argName("suffix")
                .desc(rb.getString("tmp-file-suffix"))
                .longOpt("tmp-file-suffix").build());
    }

    private static void addRelatedSOPClassOptions(Options opts) {
        opts.addOption(null, "rel-ext-neg", false, rb.getString("rel-ext-neg"));
        opts.addOption(Option.builder().hasArg().argName("file|url")
                .desc(rb.getString("rel-sop-classes"))
                .longOpt("rel-sop-classes").build());
    }

    /**
     * 流式上传：先建立连接并预注册常用 SOP 类，再边扫描边发送。
     * 避免先全量收集再传输带来的初始化延迟与内存占用。
     *
     * @param remoteAET  远端 (PACS) 的 AE Title
     * @param host       PACS 主机
     * @param port       PACS 端口
     * @param rootDir    待上传的根目录（递归扫描）
     */
    public void uploadStreaming(String remoteAET, String host, int port, File rootDir) throws Exception {
        Device device = new Device("storescu");
        Connection conn = new Connection();
        device.addConnection(conn);
        ApplicationEntity ae = new ApplicationEntity("STORESCU");
        device.addApplicationEntity(ae);
        ae.addConnection(conn);

        this.remote.setHostname(host);
        this.remote.setPort(port);
        rq.setCalledAET(remoteAET);
        rq.setCallingAET("STORESCU");

        this.attrs = new Attributes();
        preRegisterCommonStorageSOPClasses();

        ExecutorService executorService = Executors.newSingleThreadExecutor();
        ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
        device.setExecutor(executorService);
        device.setScheduledExecutor(scheduledExecutorService);
        try {
            open();
            log.info("connected to {}@{}:{}", remoteAET, host, port);
            scanAndSend(rootDir);
        } finally {
            close();
            executorService.shutdown();
            scheduledExecutorService.shutdown();
        }
    }

    /**
     * 遍历目录（含子目录），对每个符合过滤条件的 DICOM 文件立即发送。
     * 不缓冲文件清单，扫描到即发送。
     */
    private void scanAndSend(File rootDir) {
        walkAndSend(rootDir);
        try {
            as.waitForOutstandingRSP();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("sent {} files, {} MB", filesSent, totalSize / 1048576F);
    }

    private void walkAndSend(File dir) {
        File[] files = dir.listFiles();
        if (files == null)
            return;
        for (File f : files) {
            if (f.isDirectory()) {
                walkAndSend(f);
            } else if (f.getName().toUpperCase().contains(".DCM") && f.length() > 25600) {
                sendOne(f);
            }
        }
    }

    private void sendOne(File f) {
        if (!as.isReadyForDataTransfer())
            return;
        DicomInputStream in = null;
        try {
            in = new DicomInputStream(f);
            in.setIncludeBulkData(IncludeBulkData.NO);
            Attributes fmi = in.readFileMetaInformation();
            long dsPos = in.getPosition();
            String cuid = fmi != null ? fmi.getString(Tag.MediaStorageSOPClassUID) : null;
            String iuid = fmi != null ? fmi.getString(Tag.MediaStorageSOPInstanceUID) : null;
            if (cuid == null || iuid == null)
                return;
            String ts = fmi.getString(Tag.TransferSyntaxUID);
            if (ts == null)
                ts = in.getTransferSyntax();
            if (ts == null)
                ts = UID.ExplicitVRLittleEndian;
            send(f, dsPos, cuid, iuid, ts);
        } catch (Exception e) {
            log.error("failed to send {}", f, e);
        } finally {
            SafeClose.close(in);
        }
    }

    public static void main(String[] args) {
        long t1, t2;
        try {
            CommandLine cl = parseComandLine(args);
            Device device = new Device("storescu");
            Connection conn = new Connection();
            device.addConnection(conn);
            ApplicationEntity ae = new ApplicationEntity("STORESCU");
            device.addApplicationEntity(ae);
            ae.addConnection(conn);
            StoreSCU main = new StoreSCU(ae);
            configureTmpFile(main, cl);
            CLIUtils.configureConnect(main.remote, main.rq, cl);
            CLIUtils.configureBind(conn, ae, cl);
            CLIUtils.configure(conn, cl);
            main.remote.setTlsProtocols(conn.getTlsProtocols());
            main.remote.setTlsCipherSuites(conn.getTlsCipherSuites());
            configureRelatedSOPClass(main, cl);
            main.setAttributes(new Attributes());
            CLIUtils.addAttributes(main.attrs, cl.getOptionValues("s"));
            main.setUIDSuffix(cl.getOptionValue("uid-suffix"));
            main.setPriority(CLIUtils.priorityOf(cl));
            List<String> argList = cl.getArgList();
            boolean echo = argList.isEmpty();
            if (echo) {
                configureStorageSOPClasses(main, cl);
            } else {
                log.info(rb.getString("scanning"));
                t1 = System.currentTimeMillis();
                main.scanFiles(argList);
                t2 = System.currentTimeMillis();
                int n = main.filesScanned;
//                log.info();
                if (n == 0)
                    return;
                log.info(MessageFormat.format(
                        rb.getString("scanned"), n, (t2 - t1) / 1000F,
                        (t2 - t1) / n));
            }
            ExecutorService executorService = Executors
                    .newSingleThreadExecutor();
            ScheduledExecutorService scheduledExecutorService = Executors
                    .newSingleThreadScheduledExecutor();
            device.setExecutor(executorService);
            device.setScheduledExecutor(scheduledExecutorService);
            try {
                t1 = System.currentTimeMillis();
                main.open();
                t2 = System.currentTimeMillis();
                log.info(MessageFormat.format(
                        rb.getString("connected"), main.as.getRemoteAET(), t2
                                - t1));
                if (echo)
                    main.echo();
                else {
                    t1 = System.currentTimeMillis();
                    main.sendFiles();
                    t2 = System.currentTimeMillis();
                }
            } finally {
                main.close();
                executorService.shutdown();
                scheduledExecutorService.shutdown();
            }
            if (main.filesScanned > 0) {
                float s = (t2 - t1) / 1000F;
                float mb = main.totalSize / 1048576F;
                log.info(MessageFormat.format(rb.getString("sent"),
                        main.filesSent, mb, s, mb / s));
            }
        } catch (ParseException e) {
            log.error("storescu: " + e.getMessage());
            log.error(rb.getString("try"));
        } catch (Exception e) {
            log.error("storescu: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static String uidSuffixOf(CommandLine cl) {
        return cl.getOptionValue("uid-suffix");
    }

    private static void configureTmpFile(StoreSCU storescu, CommandLine cl) {
        if (cl.hasOption("tmp-file-dir"))
            storescu.setTmpFileDirectory(new File(cl
                    .getOptionValue("tmp-file-dir")));
        storescu.setTmpFilePrefix(cl.getOptionValue("tmp-file-prefix",
                "storescu-"));
        storescu.setTmpFileSuffix(cl.getOptionValue("tmp-file-suffix"));
    }

    public static void configureRelatedSOPClass(StoreSCU storescu,
                                                CommandLine cl) throws IOException {
        if (cl.hasOption("rel-ext-neg")) {
            storescu.enableSOPClassRelationshipExtNeg(true);
            Properties p = new Properties();
            CLIUtils.loadProperties(
                    cl.hasOption("rel-sop-classes") ? cl
                            .getOptionValue("rel-ext-neg")
                            : "resource:rel-sop-classes.properties", p);
            storescu.relSOPClasses.init(p);
        }
    }

    private static void configureStorageSOPClasses(StoreSCU main, CommandLine cl)
            throws Exception {
        String[] pcs = cl.getOptionValues("store-tc");
        if (pcs != null)
            for (String pc : pcs) {
                String[] ss = StringUtils.split(pc, ':');
                configureStorageSOPClass(main, ss[0], ss[1]);
            }
        String[] files = cl.getOptionValues("store-tcs");
        if (files != null)
            for (String file : files) {
                Properties p = CLIUtils.loadProperties(file, null);
                Set<Map.Entry<Object, Object>> entrySet = p.entrySet();
                for (Map.Entry<Object, Object> entry : entrySet)
                    configureStorageSOPClass(main, (String) entry.getKey(), (String) entry.getValue());
            }
    }

    private static void configureStorageSOPClass(StoreSCU main, String cuid, String tsuids0) {
        for (String tsuids2 : StringUtils.split(tsuids0, ';')) {
            main.addOfferedStorageSOPClass(CLIUtils.toUID(cuid), CLIUtils.toUIDs(tsuids2));
        }
    }

    public void addOfferedStorageSOPClass(String cuid, String... tsuids) {
        rq.addPresentationContext(new PresentationContext(
                2 * rq.getNumberOfPresentationContexts() + 1, cuid, tsuids));
    }

    public final void enableSOPClassRelationshipExtNeg(boolean enable) {
        relExtNeg = enable;
    }

    public void scanFiles(List<String> fnames) throws IOException {
        this.scanFiles(fnames, true);
    }

    public void scanFiles(List<String> fnames, boolean printout)
            throws IOException {
        tmpFile = File.createTempFile(tmpPrefix, tmpSuffix, tmpDir);
        tmpFile.deleteOnExit();
        final BufferedWriter fileInfos = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(tmpFile)));
        try {
            DicomFiles.scan(fnames, printout, new DicomFiles.Callback() {

                @Override
                public boolean dicomFile(File f, Attributes fmi, long dsPos,
                                         Attributes ds) throws IOException {
                    if (!addFile(fileInfos, f, dsPos, fmi, ds))
                        return false;

                    filesScanned++;
                    return true;
                }
            });
        } finally {
            fileInfos.close();
        }
    }

    public void sendFiles() throws IOException {
        BufferedReader fileInfos = new BufferedReader(new InputStreamReader(
                new FileInputStream(tmpFile)));
        try {
            String line;
            while (as.isReadyForDataTransfer()
                    && (line = fileInfos.readLine()) != null) {
                String[] ss = StringUtils.split(line, '\t');
                try {
//                    log.infoln("正在传输:" + new File(ss[4]).getAbsolutePath());
                    send(new File(ss[4]), Long.parseLong(ss[3]), ss[1], ss[0],
                            ss[2]);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            try {
                as.waitForOutstandingRSP();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        } finally {
            SafeClose.close(fileInfos);
        }
    }

    public boolean addFile(BufferedWriter fileInfos, File f, long endFmi,
                           Attributes fmi, Attributes ds) throws IOException {
        String cuid = fmi.getString(Tag.MediaStorageSOPClassUID);
        String iuid = fmi.getString(Tag.MediaStorageSOPInstanceUID);
        String ts = fmi.getString(Tag.TransferSyntaxUID);
        if (cuid == null || iuid == null)
            return false;

        fileInfos.write(iuid);
        fileInfos.write('\t');
        fileInfos.write(cuid);
        fileInfos.write('\t');
        fileInfos.write(ts);
        fileInfos.write('\t');
        fileInfos.write(Long.toString(endFmi));
        fileInfos.write('\t');
        fileInfos.write(f.getPath());
        fileInfos.newLine();

        if (rq.containsPresentationContextFor(cuid, ts))
            return true;

        if (!rq.containsPresentationContextFor(cuid)) {
            if (relExtNeg)
                rq.addCommonExtendedNegotiation(relSOPClasses
                        .getCommonExtendedNegotiation(cuid));
            if (!ts.equals(UID.ExplicitVRLittleEndian))
                rq.addPresentationContext(new PresentationContext(rq
                        .getNumberOfPresentationContexts() * 2 + 1, cuid,
                        UID.ExplicitVRLittleEndian));
            if (!ts.equals(UID.ImplicitVRLittleEndian))
                rq.addPresentationContext(new PresentationContext(rq
                        .getNumberOfPresentationContexts() * 2 + 1, cuid,
                        UID.ImplicitVRLittleEndian));
        }
        rq.addPresentationContext(new PresentationContext(rq
                .getNumberOfPresentationContexts() * 2 + 1, cuid, ts));
        return true;
    }

    public void echo() throws IOException, InterruptedException {
        as.cecho().next();
    }

    public void send(final File f, long fmiEndPos, String cuid, String iuid,
                     String filets) throws IOException, InterruptedException,
            ParserConfigurationException, SAXException {
        String ts = selectTransferSyntax(cuid, filets);

        if (f.getName().endsWith(".xml")) {
            Attributes parsedDicomFile = SAXReader.parse(new FileInputStream(f));
            if (CLIUtils.updateAttributes(parsedDicomFile, attrs, uidSuffix))
                iuid = parsedDicomFile.getString(Tag.SOPInstanceUID);
            if (!ts.equals(filets)) {
                Decompressor.decompress(parsedDicomFile, filets);
            }
            as.cstore(cuid, iuid, priority,
                    new DataWriterAdapter(parsedDicomFile), ts,
                    rspHandlerFactory.createDimseRSPHandler(f));
        } else {
            if (uidSuffix == null && attrs.isEmpty() && ts.equals(filets)) {
                FileInputStream in = new FileInputStream(f);
                try {
                    in.skip(fmiEndPos);
                    InputStreamDataWriter data = new InputStreamDataWriter(in);
                    as.cstore(cuid, iuid, priority, data, ts,
                            rspHandlerFactory.createDimseRSPHandler(f));
                } finally {
                    SafeClose.close(in);
                }
            } else {
                DicomInputStream in = new DicomInputStream(f);
                try {
                    in.setIncludeBulkData(IncludeBulkData.URI);
                    Attributes data = in.readDataset();
                    if (CLIUtils.updateAttributes(data, attrs, uidSuffix))
                        iuid = data.getString(Tag.SOPInstanceUID);
                    if (!ts.equals(filets)) {
                        Decompressor.decompress(data, filets);
                    }
                    as.cstore(cuid, iuid, priority,
                            new DataWriterAdapter(data), ts,
                            rspHandlerFactory.createDimseRSPHandler(f));
                } finally {
                    SafeClose.close(in);
                }
            }
        }
    }

    private String selectTransferSyntax(String cuid, String filets) {
        Set<String> tss = as.getTransferSyntaxesFor(cuid);
        if (tss.contains(filets))
            return filets;

        if (tss.contains(UID.ExplicitVRLittleEndian))
            return UID.ExplicitVRLittleEndian;

        return UID.ImplicitVRLittleEndian;
    }

    public void close() throws IOException, InterruptedException {
        if (as != null) {
            if (as.isReadyForDataTransfer())
                as.release();
            as.waitForSocketClose();
        }
    }

    public void open() throws IOException, InterruptedException,
            IncompatibleConnectionException, GeneralSecurityException {
        as = ae.connect(remote, rq);
    }

    private void onCStoreRSP(Attributes cmd, File f) {
        int status = cmd.getInt(Tag.Status, -1);
        switch (status) {
            case Status.Success:
                totalSize += f.length();
                ++filesSent;
                log.info(".");
                break;
            case Status.CoercionOfDataElements:
            case Status.ElementsDiscarded:
            case Status.DataSetDoesNotMatchSOPClassWarning:
                totalSize += f.length();
                ++filesSent;
                log.error(MessageFormat.format(rb.getString("warning"),
                        TagUtils.shortToHexString(status), f));
                log.error(String.valueOf(cmd));
                break;
            default:
                log.info("E");
                log.error(MessageFormat.format(rb.getString("error"),
                        TagUtils.shortToHexString(status), f));
                log.error(String.valueOf(cmd));
        }
    }
}
