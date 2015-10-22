===============  K9Email 修改日志 ===========
1.修改或优化功能说明
  a)位置：修改位置的类名
	(1) 变动的地方说明(增加/修改：变量&方法)
		[1] 代码

=============================================
1. master分支：github开源 Android Studio项目转换成Eclipse项目，变动的类：
   a)位置：MessagingController.java
	(1) 注释行：     -2526 -3238 -3284 -3323 -5089 -5093 -5113
		 对应的方法： builder.setVisibility()
		 错误提示：   The method setVisibility(int) is undefined for the type NotificationCompat.Builder
		 环境：       Android5.0 API19
		 级别：{FUTURE FIX -后期修复} 
    (2) 注释行：    -5119
		 对应的方法：builder.setPublicVersion()
		 错误提示：The method setPublicVersion(Notification) is undefined for the type NotificationCompat.Builder
		 级别：{FUTURE FIX -后期修复} 

2. 增加拍照来添加附件功能，变动的类
	a)AndroidMainfest.xml
		增加拍照权限：<uses-permission android:name="android.permission.CAMERA" />
	b)string.xml
		<string name="pick_photo">相册</string>
		<string name="take_photo">拍照</string>
	c)MessageCompose.java
		(1) 变量：private static final int DIALOG_CHOOSE_ATTACHMENT_SOURCE = 5;
				  private static final int ACTIVITY_REQUEST_TAKE_ATTACHMENT = 2;
		(2) 方法内增加代码：
			[1]	onCreateDialog():
					case DIALOG_CHOOSE_ATTACHMENT_SOURCE: {
						final CharSequence[] item = { getString(R.string.pick_photo), getString(R.string.take_photo) };
						return new AlertDialog.Builder(this).setTitle(R.string.add_attachment_action).setItems(item, new DialogInterface.OnClickListener() {

							public void onClick(DialogInterface dialog, int id) {
								switch (id) {
								case 0:
									onAddAttachment2("*/*");
									break;
								case 1:
									Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
									startActivityForResult(Intent.createChooser(i, null), ACTIVITY_REQUEST_TAKE_ATTACHMENT);
									break;
								default:
									break;
								}
							}
						}).setNegativeButton(R.string.cancel_action, new DialogInterface.OnClickListener() {
							public void onClick(DialogInterface dialog, int which) {
							}
						}).create();
					}
			[2] onActivityResult()
					case ACTIVITY_REQUEST_TAKE_ATTACHMENT:
						addAttachmentsFromCapture(data);
						mDraftNeedsSaving = true;
						break;
		(3)	修改方法：
			onAddAttachment()
				showDialog(DIALOG_CHOOSE_ATTACHMENT_SOURCE);// onAddAttachment2("*/*");
		(4) 增加方法：
			[1]	private void addAttachmentsFromCapture(Intent data) {
					Bitmap bitmap = (Bitmap) data.getExtras().get("data");
					String name = "IMG_" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()) + ".jpg";
					File mFile = getFile(name);
					FileOutputStream mFileOutputStream = getOutputStream(mFile, name);
					bitmap.compress(CompressFormat.JPEG, 100, mFileOutputStream);
					bitmap.recycle();
					try {
						mFileOutputStream.close();
					} catch (IOException e) {
						e.printStackTrace();
					}
					Uri uri = Uri.fromFile(mFile);
					addAttachment(uri);
				}

			[2]	private FileOutputStream getOutputStream(File mFile, String name) {
					FileOutputStream mFileOutputStream = null;
					if (mFile != null) {
						try {
							mFileOutputStream = new FileOutputStream(mFile);
						} catch (FileNotFoundException e) {
							e.printStackTrace();
						}
					} else {
						try {
							mFileOutputStream = openFileOutput(name, Context.MODE_PRIVATE);
							mFile = new File(getFilesDir(), name);
						} catch (FileNotFoundException e) {
							e.printStackTrace();
						}
					}
					return mFileOutputStream;
				}

			[3]	private File getFile(String name) {
					File mFile = null;
					String state = Environment.getExternalStorageState();
					if (Environment.MEDIA_MOUNTED.equals(state)) {
						mFile = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "AttachImg" + File.separator + name);
						if (!mFile.getParentFile().exists())
							mFile.getParentFile().mkdirs();
					}
					return mFile;
				}
3.优化图片附件打开功能：Android 4.2.2可以打开附件，Android 4.4.2，Android 5.1 无法打开
	a) 位置 AttachmentController.java
		(1) 增加sdk判断：getBestViewIntentForMimeType() 
			#1	if(Build.VERSION.SDK_INT <= Build.VERSION_CODES.JELLY_BEAN_MR1)/* 4.2.2 & higher ,image attachemnt can`t open*/
					if (contentUriActivitiesCount > 0) {
						return new IntentAndResolvedActivitiesCount(contentUriIntent, contentUriActivitiesCount);
					}
4.内嵌图片无法浏览&保存：原因在于文件格式为 application/octet-stream
	a) 位置 MessageContainerView.java
		(1) 增加方法 viewInlineImage() {灵感：在测试时，发现通过保存后的图片可以正常打开。}
			[1]	private Intent viewInlineImage(String url) {
					File tempFile = null;
					try {
						tempFile = fetchAndStoreImage(url);
					} catch (IOException e) {
						Log.e(K9.LOG_TAG, "Error while downloading image", e);
						e.printStackTrace();
					}
					Uri tempFileUri = Uri.fromFile(tempFile);
					Intent fileUriIntent = createViewIntentForFileUri("image/jpeg", tempFileUri);
					return fileUriIntent;
				}
			[2]	其余封装方法
				private Intent createViewIntentForFileUri(String mimeType, Uri uri) {
					Intent intent = new Intent(Intent.ACTION_VIEW);
					intent.setDataAndType(uri, mimeType);
					addUiIntentFlags(intent);
					return intent;
				}

				private void addUiIntentFlags(Intent intent) {
					intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
				}

				private File fetchAndStoreImage(String urlString) throws IOException {
					ContentResolver contentResolver = getContext().getContentResolver();
					Uri uri = Uri.parse(urlString);

					String fileName = getFileNameFromContentProvider(contentResolver, uri);
					String mimeType = getMimeType(contentResolver, uri, fileName);

					InputStream in = contentResolver.openInputStream(uri);
					try {
						String fileNameWithExtension = getFileNameWithExtension(fileName, mimeType);
						return writeFileToStorage(fileNameWithExtension, in);
					} finally {
						in.close();
					}
				}

				private File writeFileToStorage(String fileName, InputStream in) throws IOException {
					String sanitized = FileHelper.sanitizeFilename(fileName);

					File directory = new File(K9.getAttachmentDefaultPath());
					File file = FileHelper.createUniqueFile(directory, sanitized);

					FileOutputStream out = new FileOutputStream(file);
					try {
						IOUtils.copy(in, out);
						out.flush();
					} finally {
						out.close();
					}

					return file;
				}

				private String getFileNameWithExtension(String fileName, String mimeType) {
					//        if (fileName.indexOf('.') != -1) {
					//            return fileName;
					//        }

					// Use JPEG as fallback
					String extension = "jpeg";
					if (mimeType != null) {
						String extensionFromMimeType = MimeUtility.getExtensionByMimeType(mimeType);
						if (extensionFromMimeType != null) {
							extension = extensionFromMimeType;
						}
					}

					return fileName + "." + extension;
				}

				private String getMimeType(ContentResolver contentResolver, Uri uri, String fileName) {
					String mimeType = null;
					if (fileName.indexOf('.') == -1) {
						mimeType = contentResolver.getType(uri);
					}

					return mimeType;
				}

				private String getFileNameFromContentProvider(ContentResolver contentResolver, Uri uri) {
					String displayName = "saved_image";
					int DISPLAY_NAME_INDEX = 1;
					String[] ATTACHMENT_PROJECTION = new String[] {AttachmentProvider.AttachmentProviderColumns._ID,
							AttachmentProvider.AttachmentProviderColumns.DISPLAY_NAME};
					Cursor cursor = contentResolver.query(uri, ATTACHMENT_PROJECTION, null, null, null);
					if (cursor != null) {
						try {
							if (cursor.moveToNext() && !cursor.isNull(DISPLAY_NAME_INDEX)) {
								displayName = cursor.getString(DISPLAY_NAME_INDEX);
							}
						} finally {
							cursor.close();
						}
					}

					return displayName;
				}
			[3]	调用-增加方法
					if (!externalImage) {
						// Grant read permission if this points to our AttachmentProvider
				#1		intent = viewInlineImage(url);
						intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
					}
4.1	内嵌图片，作为附件形式的打开和保存处理。
	a)	正则表达式判断是否为内嵌图片：AttachmentController.java
		[1]	 /* 通过attachment.displayName="3000FA10@98D76401.E35F2456"和mimeType="application/octet-stream"判断为内嵌图片 */
			private boolean isInlineResource(String displayName, String mimeType){
				Pattern p = Pattern.compile("^[A-Z0-9]{8}@[A-Z0-9]{8}\\.[A-Z0-9]{8}$");
				Matcher m = p.matcher(displayName);
				return MimeUtility.isDefaultMimeType(mimeType) && m.matches();
			}
		[2]	 private IntentAndResolvedActivitiesCount getBestViewIntentForMimeType(String mimeType) {
				Intent contentUriIntent = createViewIntentForAttachmentProviderUri(mimeType);
				int contentUriActivitiesCount = getResolvedIntentActivitiesCount(contentUriIntent);
			#1	boolean isInline = isInlineResource(attachment.displayName, mimeType);
			#2	if(!isInline && Build.VERSION.SDK_INT <= Build.VERSION_CODES.JELLY_BEAN_MR1)/* 4.2.2 & higher ,image attachemnt can`t open*/
					if (contentUriActivitiesCount > 0) {
						return new IntentAndResolvedActivitiesCount(contentUriIntent, contentUriActivitiesCount);
					}

			#3	File tempFile = TemporaryAttachmentStore.getFile(context, isInline ? attachment.displayName + ".jpeg" : attachment.displayName);
				...
		
		
	
5.	优化附件视图：缩小图片大小，使整体更和谐。
	a)	修改附件视图xml文件：\layout\message_view_attachment.xml
	b)	增加附件视图Button的Selector作为Background：\drawable\attachment_view_button.xml
6.	下载完整附件处理：修改按钮视图，增加下载滚动效果；附件未全部下载的，全部隐藏,直到下载完整后才显示。
	a)	修改按钮视图：\layout\message.xml
		[1]	...
			<Button android:id="@+id/download_remainder"
				android:text="@string/attachment_not_download"
				android:layout_height="wrap_content"
				android:drawableLeft="@drawable/ic_email_attachment_small"
				android:visibility="gone"
				android:background="@drawable/attachment_view_button"
				android:layout_width="fill_parent"
				android:paddingLeft="10dp"
				android:layout_margin="4dp"/>
				...
	b)	增加按钮滚动条效果:  增加Animation-list动画
		[1]	MessageTopView.java
			public void disableDownloadButton() {
				mDownloadRemainder.setEnabled(false);
			#1	mDownloadRemainder.setText(R.string.attachment_loading);
			#2	AnimationDrawable frameAnimation = (AnimationDrawable) getResources().getDrawable(R.drawable.ic_notify_check_mail);
			#3	mDownloadRemainder.setCompoundDrawablesWithIntrinsicBounds(frameAnimation, null, null, null);
			#4	if(frameAnimation != null)
			#5		frameAnimation.start();
			}
	c)	 隐藏未完整下载的附件
		[1]	增加是否显示附件标志：MessageTopView.java
			public void setMessage(Account account, MessageViewInfo messageViewInfo)
					throws MessagingException {
				resetView();

				ShowPictures showPicturesSetting = account.getShowPictures();
				boolean automaticallyLoadPictures =
						shouldAutomaticallyLoadPictures(showPicturesSetting, messageViewInfo.message);
			#1	boolean isShowAttachmentView = messageViewInfo.message.isSet(Flag.X_DOWNLOADED_FULL);/* 判断附件下载是否完整，if true,then true */
				for (MessageViewContainer container : messageViewInfo.containers) {
					MessageContainerView view = (MessageContainerView) mInflater.inflate(R.layout.message_container, null);
					boolean displayPgpHeader = account.isOpenPgpProviderConfigured();
					view.displayMessageViewContainer(container, automaticallyLoadPictures, this, attachmentCallback,
			#2				openPgpHeaderViewCallback, displayPgpHeader, isShowAttachmentView);

					containerViews.addView(view);
				}

			}
		[2]	隐藏附件
			public void displayMessageViewContainer(MessageViewContainer messageViewContainer,
					boolean automaticallyLoadPictures, ShowPicturesController showPicturesController,
					AttachmentViewCallback attachmentCallback, OpenPgpHeaderViewCallback openPgpHeaderViewCallback,
			#1		boolean displayPgpHeader, boolean isShowAttachmentView) throws MessagingException {

				this.attachmentCallback = attachmentCallback;

				resetView();

				WebViewClient webViewClient = K9WebViewClient.newInstance(messageViewContainer.rootPart);
				mMessageContentView.setWebViewClient(webViewClient);

			#2	boolean hasAttachments = !messageViewContainer.attachments.isEmpty() && isShowAttachmentView;
				if (hasAttachments) {
					renderAttachments(messageViewContainer);
				}

				mHiddenAttachments.setVisibility(View.GONE);
			...
7.	>>>

	位置：MessageViewFragment.onClick(){onDownloadRemainder();//Button：下载完整附件}
	SingleMessageView onShowAttachments() 显示附件
	Intent { act=android.intent.action.VIEW dat=content://com.fsck.k9.attachmentprovider/49341c69-3728-4d74-89df-3390c804e60f/8/VIEW/image/png/QQ截图20150821123354.png typ=image/png flg=0x80001 }
	content://com.fsck.k9.attachmentprovider/49341c69-3728-4d74-89df-3390c804e60f/8/VIEW/image%2Fpng/QQ%E6%88%AA%E5%9B%BE20150821123354.png
	
	/storage/emulated/0/Android/data/com.fsck.k9/cache/attachments/log.txt
	/data/data/com.fsck.k9/cache/body1989742193.tmp
	/data/data/com.fsck.k9/databases/f7def8e9-840e-41fe-aa55-3214bd2b40ff.db_att/62
{配置项}
【DELETE】
		WelcomeMessage.java  L37 配置：模拟点击事件Next
		AccountSetupBasics.java  L108 配置：Email & Password； L94 配置：模拟点击事件Next
		AccountSetupName.java L42 配置：Name ；L75 配置：模拟点击事件Next
		  

