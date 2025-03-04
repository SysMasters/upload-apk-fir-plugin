package com.demo.plugin
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task

class PluginImpl implements Plugin<Project> {
    UploadApkPluginExtension extension

    @Override
    void apply(Project project) {
        extension = project.extensions.create('uploadApk', UploadApkPluginExtension.class, project.getObjects())
        if (project.android.hasProperty("applicationVariants")) {
            project.android.applicationVariants.all { variant ->

                Task uploadFir = project.task("assemble${variant.name.capitalize()}Fir").doLast {
                    println("开始上传Fir")
                    def (String appPackage, String apiTokenFir, String apkPath, String fileName, String appName, String appVersion, String appBuild, String apkIconPath) = getParamsFir(project, variant)
                    OkHttpUtil okHttpUtil = new OkHttpUtil()
                    BundleApp bundleApp = okHttpUtil.getCert(appPackage,apiTokenFir)
                    println("获取凭证信息成功")
                    BundleApp.CertBean certBean = bundleApp.getCert()

                    // 上传apk
                    println("上传apk中...")
                    String key = certBean.getBinary().getKey()
                    String token = certBean.getBinary().getToken()
                    String upload_url = certBean.getBinary().getUpload_url()

                    String jsonApk = okHttpUtil.uploadApk(apkPath,key,token,appName,appVersion,appBuild,fileName,upload_url)
                    println("上传apk文件返回结果:$jsonApk")

                    // 上传icon
                    println("上传Icon中...")
                    String keyIcon = certBean.getIcon().getKey()
                    String tokenIcon = certBean.getIcon().getToken()
                    String upload_urlIcon = certBean.getIcon().getUpload_url()

                    String jsonIcon = okHttpUtil.uploadIcon(apkIconPath,keyIcon,tokenIcon,upload_urlIcon)
                    println("上传Icon返回结果:$jsonIcon")

                    ApkInfo apkInfo = okHttpUtil.getApkUrl(appPackage,apiTokenFir)
                    println("下载链接:${apkInfo.installUrl}")

                    sendDingTalk(appBuild, appVersion, okHttpUtil, apkInfo.installUrl,apkPath,"")
                }

                // 在assembleDebug执行后执行
                uploadFir.dependsOn project.tasks["assemble${variant.name.capitalize()}"]

                Task uploadPgyer = project.task("assemble${variant.name.capitalize()}Pgyer").doLast {
                    println("开始上传Pgyer")
                    def (String appPackage,String apkPath,String fileName,String appVersion,String appBuild,String apiKey) = getParamsPgyer(project,variant)
                    OkHttpUtil okHttpUtil = new OkHttpUtil()
                    UploadApp uploadApp = okHttpUtil.uploadApkPgyer(apkPath, apiKey,fileName)
                    println("上传apk文件返回结果:$uploadApp")
                    sendDingTalk(appBuild, appVersion, okHttpUtil, "https://pgyer.com/${uploadApp.data.buildShortcutUrl}",apkPath,uploadApp.data.buildQRCodeURL)
                }

                // 在assembleDebug执行后执行
                uploadPgyer.dependsOn project.tasks["assemble${variant.name.capitalize()}"]


                Task uploadFtp = project.task("assemble${variant.name.capitalize()}Ftp").doLast {
                    println("sftp开始上传...")
                    def (String appPackage,String apkPath,String fileName,String appVersion,String appBuild,String username,String password,String host,String port,String remotePath,String installUrl,String qrApiUrl) = getParamsSftp(project,variant)
                    try{
                        int portNumber = Integer.parseInt(port)
                        SftpUtil sftpUtil = new SftpUtil(username,password,host,portNumber)
                        sftpUtil.login()
                        sftpUtil.upload(remotePath, apkPath)
                        sftpUtil.logout()
                        String installApkUrl = "${installUrl}${fileName}"
                        String qrUrl = "${qrApiUrl}${installApkUrl}"
                        OkHttpUtil okHttpUtil = new OkHttpUtil()
                        sendDingTalk(appBuild, appVersion, okHttpUtil, installApkUrl, apkPath, qrUrl)
                    }
                    catch (NumberFormatException e){
                        println("端口号应该为数字")
                    }
                }
                uploadFtp.dependsOn project.tasks["assemble${variant.name.capitalize()}"]


                Task uploadGitee = project.task("assemble${variant.name.capitalize()}Gitee").doLast {
                    println("gitee开始上传...")
                    def (String appPackage,String apkPath,String fileName,String appVersion,String appBuild,String accessToken,String message,String owner,String repo,String qrApiUrl) = getParamsGitee(project,variant)

                    UploadUtil uploadUtil = new UploadUtil(accessToken, message)
                    uploadUtil.upload(apkPath, fileName)
                    String installApkUrl = "https://gitee.com/${owner}/${repo}/raw/master/${fileName}"
                    String qrUrl = "${qrApiUrl}${installApkUrl}"
                    OkHttpUtil okHttpUtil = new OkHttpUtil()
                    sendDingTalk(appBuild, appVersion, okHttpUtil, installApkUrl, apkPath, qrUrl)
                }
                uploadGitee.dependsOn project.tasks["assemble${variant.name.capitalize()}"]
            }
        }
    }

    private void sendDingTalk(String appBuild, String appVersion, OkHttpUtil okHttpUtil, String installUrl,String apkPath, String qr) {
        def (String content, String title,String qrTitle,String qrContent,String webHook, boolean isAtAll, List<String> atMobiles) = getDingTalkParams()
        String dingTalkMsg = "点击跳转下载链接(版本号:$appBuild    版本名称:$appVersion)"
        if (content.length() > 0) {
            dingTalkMsg = "${dingTalkMsg}，此次更新:$content"
        }
        if (!qr.isEmpty()){
            println("发送二维码地址:$qr")
            okHttpUtil.sendDingTalkLink(qrContent, qrTitle, qr, webHook)
        }
        /**
         * 发送钉钉消息
         */
        okHttpUtil.sendDingTalkLink(dingTalkMsg, title, installUrl, webHook)
        String md5 = MD5Util.calcMD5(apkPath)
        okHttpUtil.sendDingTalkMsg("${content}md5:${md5}", webHook, isAtAll, atMobiles)
    }

    private List getDingTalkParams() {
        String webHook = extension.getDingTalkExtension().getWebHook()
        String title = extension.getDingTalkExtension().getTitle()
        String content = extension.getDingTalkExtension().getContent()
        String isAtAll = extension.getDingTalkExtension().getIsAtAll()
        String qrTitle = extension.getDingTalkExtension().getQrTitle()
        String qrContent = extension.getDingTalkExtension().getQrContent()
        List<String> atMobiles = extension.getDingTalkExtension().getAtMobiles()
        [content, title, qrTitle, qrContent, webHook, isAtAll, atMobiles]
    }

    private List getParamsGitee(Project project, variant){
        String accessToken = extension.getGiteeExtension().getAccessToken()
        String message = extension.getGiteeExtension().getMessage()
        String owner = extension.getGiteeExtension().getOwner()
        String repo = extension.getGiteeExtension().getRepo()
        String qrApiUrl = extension.getGiteeExtension().getQrApiUrl()

        def (String appPackage, String apkPath, String fileName, String appVersion, String appBuild) = getCommon(project, variant)
        [appPackage, apkPath, fileName, appVersion, appBuild, accessToken, message, owner, repo, qrApiUrl]
    }

    private List getParamsSftp(Project project, variant){
        String username = extension.getSftpExtension().getUsername()
        String password = extension.getSftpExtension().getPassword()
        String host = extension.getSftpExtension().getHost()
        String port = extension.getSftpExtension().getPort()
        String remotePath = extension.getSftpExtension().getRemotePath()
        String installUrl = extension.getSftpExtension().getInstallUrl()
        String qrApiUrl = extension.getSftpExtension().getQrApiUrl()

        def (String appPackage, String apkPath, String fileName, String appVersion, String appBuild) = getCommon(project, variant)
        [appPackage, apkPath, fileName, appVersion, appBuild, username, password, host, port, remotePath, installUrl, qrApiUrl]
    }
    private List getParamsPgyer(Project project, variant){
        String apiKey = extension.getPgyerExtension().getApiKey()
        def (String appPackage, String apkPath, String fileName, String appVersion, String appBuild) = getCommon(project, variant)
        [appPackage, apkPath, fileName, appVersion, appBuild, apiKey]
    }
    private List getParamsFir(Project project, variant) {
        String appName = extension.getFirExtension().getAppName()
        def (String appPackage, String apkPath, String fileName, String appVersion, String appBuild) = getCommon(project, variant)
        String apkIconPath = project.android.applicationVariants.first().outputs.first().outputFile.parent.split("build")[0] + extension.getFirExtension().getIconPath()
        String apiTokenFir = extension.getFirExtension().getToken()
        // 获取上传凭证
//                        println("appName:$appName")
//                        println("appPackage:$appPackage")
//                        println("appVersion:${appVersion}")
//                        println("appBuild:${appBuild}")
//                        println("apiTokenFir:${apiTokenFir}")
//                        println("apkIconPath:${apkIconPath}")
//        println("文件路径:$apkPath")
//        println("文件名称:$fileName")
        [appPackage, apiTokenFir, apkPath, fileName, appName, appVersion, appBuild, apkIconPath]
    }

    private List getCommon(Project project, variant) {
        String appPackage = project.android.defaultConfig.applicationId
        String appVersion = project.android.defaultConfig.versionName
        String appBuild = project.android.defaultConfig.versionCode
        String apkPath = variant.outputs.first().outputFile
        File file = new File(apkPath)
        String fileName = file.getName()
        [appPackage, apkPath, fileName, appVersion, appBuild]
    }


}
