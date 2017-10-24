package action;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.ibatis.session.SqlSession;
import org.grain.httpserver.HttpConfig;
import org.grain.mariadb.MybatisManager;

import config.CommonConfigBox;
import config.FileBaseConfig;
import config.UserFileConfig;
import dao.dao.base.FileBaseMapper;
import dao.model.base.FileBase;
import dao.model.ext.UserFileExt;
import tool.StringUtil;
import tool.TimeUtils;
import util.IdUtil;

public class UserFileAction implements IUserFileAction {
	public static String FILE_BASE_PATH;
	public static Map<String, UserFileExt> userFileMap = new ConcurrentHashMap<String, UserFileExt>();

	@Override
	public UserFileExt getUserFile(String userFileId) {
		if (StringUtil.stringIsNull(userFileId)) {
			return null;
		}
		return userFileMap.get(userFileId);
	}

	@Override
	public UserFileExt getUserFileComplete(String userFileId) {
		if (StringUtil.stringIsNull(userFileId)) {
			return null;
		}
		UserFileExt userFile = userFileMap.get(userFileId);
		if (userFile != null) {
			if (userFile.getFileBase().getFileBaseState().intValue() == 1) {
				return userFile;
			}
		}
		return null;
	}

	@Override
	public UserFileExt createUserFile(String userFileName, String userFoldParentId, String createUserId, String fileBaseMd5, long fileBaseTotalSize, FileBase fileBase) {
		if (StringUtil.stringIsNull(userFileName) || StringUtil.stringIsNull(userFoldParentId) || StringUtil.stringIsNull(createUserId) || StringUtil.stringIsNull(fileBaseMd5)) {
			return null;
		}
		UserFileExt userFile = new UserFileExt();
		Date date = new Date();
		userFile.setUserFileId(IdUtil.getUuid());
		userFile.setUserFileName(userFileName);
		userFile.setUserFoldParentId(userFoldParentId);
		userFile.setUserFileCreateTime(date);
		userFile.setUserFileUpdateTime(date);
		userFile.setUserFileState((byte) UserFileConfig.STATE_CAN_USE);
		userFile.setCreateUserId(createUserId);
		if (fileBase == null) {
			FileBase newFileBase = new FileBase();
			newFileBase.setFileBaseId(IdUtil.getUuid());
			String fileBaseRealPath = createFile(getFileName(userFileName, newFileBase.getFileBaseId()));
			if (fileBaseRealPath == null) {
				return null;
			}
			newFileBase.setFileBaseRealPath(fileBaseRealPath);
			newFileBase.setFileBaseMd5(fileBaseMd5);
			newFileBase.setFileBaseState((byte) FileBaseConfig.STATE_UPLOADING);
			newFileBase.setFileBaseTotalSize(fileBaseTotalSize);
			newFileBase.setFileBasePos(0L);
			newFileBase.setFileBaseCreateTime(date);
			newFileBase.setFileBaseNextUploadTime(date);
			userFile.setFileBaseId(newFileBase.getFileBaseId());
			userFile.setFileBase(newFileBase);
		} else {
			userFile.setFileBaseId(fileBase.getFileBaseId());
			userFile.setFileBase(fileBase);
		}
		userFileMap.put(userFile.getUserFileId(), userFile);
		return userFile;
	}

	@Override
	public boolean changeFileBase(UserFileExt userFile, FileBase fileBase) {
		userFile.setFileBaseId(fileBase.getFileBaseId());
		userFile.setFileBase(fileBase);
		return true;

	}

	public static boolean updateFile(File file, File chunkFile) {
		FileOutputStream fileOutputStream = null;
		FileInputStream fileInputStream = null;
		try {
			fileOutputStream = new FileOutputStream(file, true);
			fileInputStream = new FileInputStream(chunkFile);

			byte[] buffer = new byte[CommonConfigBox.ONCE_WRITE_FILE_SIZE];
			int bytesRead = -1;
			while ((bytesRead = fileInputStream.read(buffer)) != -1) {
				fileOutputStream.write(buffer, 0, bytesRead);
			}
			fileOutputStream.flush();
			return true;
		} catch (Exception e) {
			MybatisManager.log.error("修改文件失败", e);
			return false;
		} finally {
			if (fileInputStream != null) {
				try {
					fileInputStream.close();
				} catch (IOException e) {
					MybatisManager.log.error("关闭块文件输入流失败", e);
				}
			}
			if (fileOutputStream != null) {
				try {
					fileOutputStream.close();
				} catch (IOException e) {
					MybatisManager.log.error("关闭输出流失败", e);
				}
			}
		}
	}

	public static boolean updateUserFile(UserFileExt userFile, int uploadLength) {
		FileBase fileBase = new FileBase();
		fileBase.setFileBaseId(userFile.getFileBase().getFileBaseId());
		Date date = new Date();
		fileBase.setFileBasePos(userFile.getFileBase().getFileBasePos() + uploadLength);
		// 已完成
		if (fileBase.getFileBasePos().longValue() == userFile.getFileBase().getFileBaseTotalSize().longValue()) {
			fileBase.setFileBaseNextUploadTime(null);
			fileBase.setFileBaseCompleteTime(date);
			fileBase.setFileBaseState((byte) FileBaseConfig.STATE_COMPLETE);
		} else {
			long fileBaseNextUploadTimeLong = date.getTime() + CommonConfigBox.WAIT_TIME;
			Date fileBaseNextUploadTime = new Date(fileBaseNextUploadTimeLong);
			fileBase.setFileBaseNextUploadTime(fileBaseNextUploadTime);
		}

		SqlSession sqlSession = null;
		try {
			sqlSession = MybatisManager.getSqlSession();
			FileBaseMapper fileBaseMapper = sqlSession.getMapper(FileBaseMapper.class);
			int result = fileBaseMapper.updateByPrimaryKeySelective(fileBase);
			if (result != 1) {
				MybatisManager.log.warn("修改基础文件失败");
				return false;
			}
			sqlSession.commit();
		} catch (Exception e) {
			if (sqlSession != null) {
				sqlSession.rollback();
			}
			MybatisManager.log.error("修改基础文件异常", e);
			return false;
		} finally {
			if (sqlSession != null) {
				sqlSession.close();
			}
		}
		return true;
	}

	public static void createFileBaseDir() {
		FILE_BASE_PATH = HttpConfig.PROJECT_PATH + "/" + CommonConfigBox.FILE_BASE_PATH;
		File file = new File(FILE_BASE_PATH);
		if (!file.exists()) {
			file.mkdirs();
		}
	}

	public static String getFileName(String userFileName, String fileBaseId) {
		int postfixIndex = userFileName.lastIndexOf(".");
		if (postfixIndex == -1) {
			return fileBaseId;
		} else {
			String postfix = userFileName.substring(postfixIndex);
			return fileBaseId + postfix;
		}
	}

	public static String getFoldName() {
		Date date = new Date();
		String foldName = TimeUtils.dateToStringDay(date);
		return foldName;
	}

	public static String createFile(String fileName) {
		String foldName = getFoldName();
		boolean result = createFold(foldName);
		if (!result) {
			return null;
		}
		File file = new File(FILE_BASE_PATH + "/" + foldName + "/" + fileName);
		try {
			result = file.createNewFile();
			if (result) {
				return foldName + "/" + fileName;
			} else {
				return null;
			}
		} catch (IOException e) {
			MybatisManager.log.error("创建文件异常", e);
			return null;
		}
	}

	public static File getFile(String fileBaseRealPath) {
		File file = new File(FILE_BASE_PATH + "/" + fileBaseRealPath);
		if (file.exists()) {
			return file;
		} else {
			return null;
		}
	}

	public static boolean deleteFile(String fileBaseRealPath) {
		File file = new File(FILE_BASE_PATH + "/" + fileBaseRealPath);
		try {
			return file.delete();
		} catch (Exception e) {
			MybatisManager.log.error("删除文件异常", e);
			return false;
		}

	}

	public static boolean createFold(String name) {
		File file = new File(FILE_BASE_PATH + "/" + name);
		try {
			if (!file.exists()) {
				file.mkdirs();
			}
			return true;
		} catch (Exception e) {
			MybatisManager.log.error("创建文件夹异常", e);
			return false;
		}

	}

}
