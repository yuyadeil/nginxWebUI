package com.cym.config;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipInputStream;

import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Init;
import org.noear.solon.annotation.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cym.model.Admin;
import com.cym.model.Basic;
import com.cym.model.Cert;
import com.cym.model.Http;
import com.cym.service.BasicService;
import com.cym.service.ConfService;
import com.cym.service.SettingService;
import com.cym.sqlhelper.utils.SqlHelper;
import com.cym.utils.EncodePassUtils;
import com.cym.utils.MessageUtils;
import com.cym.utils.NginxUtils;
import com.cym.utils.SystemTool;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.resource.ClassPathResource;
import cn.hutool.core.util.CharsetUtil;
import cn.hutool.core.util.RuntimeUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.ZipUtil;

@Component
public class InitConfig {
	Logger logger = LoggerFactory.getLogger(this.getClass());
	@Inject
	MessageUtils m;

	@Inject
	HomeConfig homeConfig;

	@Inject
	VersionConfig versionConfig;

	@Inject
	SettingService settingService;
	@Inject
	BasicService basicService;
	@Inject
	SqlHelper sqlHelper;
	@Inject
	ConfService confService;

	@Inject("${project.findPass}")
	Boolean findPass;

	@Init
	public void start() throws Throwable {
		// 找回密码
		if (findPass) {
			List<Admin> admins = sqlHelper.findAll(Admin.class);
			for (Admin admin : admins) {
				System.out.println(m.get("adminStr.name") + ":" + admin.getName() + " " + m.get("adminStr.pass") + ":" + EncodePassUtils.defaultPass);
				admin.setAuth(false); // 关闭二次验证
				admin.setPass(EncodePassUtils.encodeDefaultPass());
				sqlHelper.updateById(admin);
			}
			System.exit(1);
		}

		// 初始化base值
		Long count = sqlHelper.findAllCount(Basic.class);
		if (count == 0) {
			List<Basic> basics = new ArrayList<Basic>();
			basics.add(new Basic("worker_processes", "auto", 1l));
			basics.add(new Basic("events", "{\r\n    worker_connections  1024;\r\n    accept_mutex on;\r\n}", 2l));
			sqlHelper.insertAll(basics);
		}

		// 初始化http值
		count = sqlHelper.findAllCount(Http.class);
		if (count == 0) {
			List<Http> https = new ArrayList<Http>();
			https.add(new Http("include", "mime.types", 0l));
			https.add(new Http("default_type", "application/octet-stream", 1l));
			sqlHelper.insertAll(https);
		}

		// 释放基础nginx配置文件
		if (!FileUtil.exist(homeConfig.home + "fastcgi.conf")) {
			ClassPathResource resource = new ClassPathResource("conf.zip");
			InputStream inputStream = resource.getStream();
			ZipUtil.unzip(inputStream, new File(homeConfig.home), CharsetUtil.defaultCharset());
		}
		if (!FileUtil.exist(homeConfig.home + "nginx.conf")) {
			ClassPathResource resource = new ClassPathResource("nginx.conf");
			InputStream inputStream = resource.getStream();
			FileUtil.writeFromStream(inputStream, homeConfig.home + "nginx.conf");

		}

		// 设置nginx配置文件
		String nginxPath = settingService.get("nginxPath");
		if (StrUtil.isEmpty(nginxPath)) {
			nginxPath = homeConfig.home + "nginx.conf";
			// 设置nginx.conf路径
			settingService.set("nginxPath", nginxPath);
		}

		// 释放acme全新包
		String acmeShDir = homeConfig.home + ".acme.sh" + File.separator;
		ClassPathResource resource = new ClassPathResource("acme.zip");
		InputStream inputStream = resource.getStream();
		ZipUtil.unzip(inputStream, new File(acmeShDir), CharsetUtil.defaultCharset());


		// 全局黑白名单
		if (settingService.get("denyAllow") == null) {
			settingService.set("denyAllow", "0");
		}
		if (settingService.get("denyAllowStream") == null) {
			settingService.set("denyAllowStream", "0");
		}

		if (SystemTool.isLinux()) {
			// 查找ngx_stream_module模块
			if (!basicService.contain("ngx_stream_module.so") && FileUtil.exist("/usr/lib/nginx/modules/ngx_stream_module.so")) {
				Basic basic = new Basic("load_module", "/usr/lib/nginx/modules/ngx_stream_module.so", -10l);
				sqlHelper.insert(basic);
			}

			// 判断是否存在nginx命令
			if (hasNginx() && StrUtil.isEmpty(settingService.get("nginxExe"))) {
				// 设置nginx执行文件
				settingService.set("nginxExe", "nginx");
			}

			// 尝试启动nginx
			String nginxExe = settingService.get("nginxExe");
			String nginxDir = settingService.get("nginxDir");

			logger.info("nginxIsRun:" + NginxUtils.isRun());
			if (!NginxUtils.isRun() && StrUtil.isNotEmpty(nginxExe) && StrUtil.isNotEmpty(nginxPath)) {
				String cmd = nginxExe + " -c " + nginxPath;

				if (StrUtil.isNotEmpty(nginxDir)) {
					cmd += " -p " + nginxDir;
				}
				logger.info("runCmd:" + cmd);
				RuntimeUtil.execForStr("/bin/sh", "-c", cmd);
			}
		}

		// 展示logo
		showLogo();
	}



	private boolean hasNginx() {
		String rs = RuntimeUtil.execForStr("which nginx");
		if (StrUtil.isNotEmpty(rs)) {
			return true;
		}

		return false;
	}

	private void showLogo() throws IOException {
		ClassPathResource resource = new ClassPathResource("banner.txt");
		BufferedReader reader = resource.getReader(StandardCharsets.UTF_8);
		String str = null;
		StringBuilder stringBuilder = new StringBuilder();
		// 使用readLine() 比较方便的读取一行
		while (null != (str = reader.readLine())) {
			stringBuilder.append(str).append("\n");
		}
		reader.close();// 关闭流

		stringBuilder.append("nginxWebUI ").append(versionConfig.currentVersion).append("\n");

		logger.info(stringBuilder.toString());

	}

}
