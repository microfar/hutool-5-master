package cn.hutool.extra.compress.extractor;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.IORuntimeException;
import cn.hutool.core.io.IoUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.lang.Filter;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.compress.CompressException;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

/**
 * 数据解压器，即将归档打包的数据释放
 *
 * @author looly
 * @since 5.5.0
 */
public class StreamExtractor implements Extractor {

	private final ArchiveInputStream in;

	/**
	 * 构造
	 *
	 * @param charset 编码
	 * @param file    包文件
	 */
	public StreamExtractor(Charset charset, File file) {
		this(charset, null, file);
	}

	/**
	 * 构造
	 *
	 * @param charset      编码
	 * @param archiverName 归档包格式，null表示自动检测
	 * @param file         包文件
	 */
	public StreamExtractor(Charset charset, String archiverName, File file) {
		this(charset, archiverName, FileUtil.getInputStream(file));
	}

	/**
	 * 构造
	 *
	 * @param charset 编码
	 * @param in      包流
	 */
	public StreamExtractor(Charset charset, InputStream in) {
		this(charset, null, in);
	}

	/**
	 * 构造<br>
	 * 如果抛出异常，则提供的流将被关闭
	 *
	 * @param charset      编码
	 * @param archiverName 归档包格式，null表示自动检测
	 * @param in           包流
	 */
	public StreamExtractor(Charset charset, String archiverName, InputStream in) {
		// issue#2736 自定义ArchiveInputStream
		if (in instanceof ArchiveInputStream) {
			this.in = (ArchiveInputStream) in;
			return;
		}

		final ArchiveStreamFactory factory = new ArchiveStreamFactory(charset.name());
		try {
			in = IoUtil.toBuffered(in);
			if (StrUtil.isBlank(archiverName)) {
				this.in = factory.createArchiveInputStream(in);
			} else if ("tgz".equalsIgnoreCase(archiverName) || "tar.gz".equalsIgnoreCase(archiverName)) {
				//issue#I5J33E，支持tgz格式解压
				try {
					this.in = new TarArchiveInputStream(new GzipCompressorInputStream(in));
				} catch (IOException e) {
					throw new IORuntimeException(e);
				}
			} else {
				this.in = factory.createArchiveInputStream(archiverName, in);
			}
		} catch (ArchiveException e) {
			// issue#2384，如果报错可能持有文件句柄，导致无法删除文件
			IoUtil.close(in);
			throw new CompressException(e);
		}
	}

	/**
	 * 释放（解压）到指定目录，结束后自动关闭流，此方法只能调用一次
	 *
	 * @param targetDir 目标目录
	 * @param filter    解压文件过滤器，用于指定需要释放的文件，null表示不过滤。当{@link Filter#accept(Object)}为true时释放。
	 */
	@Override
	public void extract(File targetDir, int stripComponents, Filter<ArchiveEntry> filter) {
		try {
			extractInternal(targetDir, stripComponents, filter);
		} catch (IOException e) {
			throw new IORuntimeException(e);
		} finally {
			close();
		}
	}

	/**
	 * 释放（解压）到指定目录
	 *
	 * @param targetDir       目标目录
	 * @param stripComponents 清除(剥离)压缩包里面的 n 级文件夹名
	 * @param filter          解压文件过滤器，用于指定需要释放的文件，null表示不过滤。当{@link Filter#accept(Object)}为true时释放。
	 * @throws IOException IO异常
	 */
	private void extractInternal(File targetDir, int stripComponents, Filter<ArchiveEntry> filter) throws IOException {
		Assert.isTrue(null != targetDir && ((false == targetDir.exists()) || targetDir.isDirectory()), "target must be dir.");
		final ArchiveInputStream in = this.in;
		ArchiveEntry entry;
		File outItemFile;
		while (null != (entry = in.getNextEntry())) {
			if (null != filter && false == filter.accept(entry)) {
				continue;
			}
			if (false == in.canReadEntryData(entry)) {
				// 无法读取的文件直接跳过
				continue;
			}
			String entryName = this.stripName(entry.getName(), stripComponents);
			if (entryName == null) {
				// 剥离文件夹层级
				continue;
			}
			outItemFile = FileUtil.file(targetDir, entryName);
			if (entry.isDirectory()) {
				// 创建对应目录
				//noinspection ResultOfMethodCallIgnored
				outItemFile.mkdirs();
			} else {
				FileUtil.writeFromStream(in, outItemFile, false);
			}
		}
	}

	@Override
	public void close() {
		IoUtil.close(this.in);
	}
}
