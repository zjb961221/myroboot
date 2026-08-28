INSERT INTO faq (category, question, answer, keywords)
SELECT 'APP问题', 'APP登录不上怎么办？', '请先确认手机网络正常、服务器地址配置正确，并尝试退出后重新登录。如仍无法解决，请提供矿井名称、手机型号、APP版本、报错截图和出现问题的时间。', 'APP,登录,打不开,进不去,手机端'
WHERE NOT EXISTS (SELECT 1 FROM faq WHERE question = 'APP登录不上怎么办？');

INSERT INTO faq (category, question, answer, keywords)
SELECT '视频问题', '视频在线但无法播放怎么办？', '请先确认摄像头在线状态，再检查矿端视频平台、网络连通性和播放服务。如仍无法解决，请提供矿井名称、摄像头名称、故障时间和截图。', '视频,摄像头,黑屏,播放失败,WVP,GB28181'
WHERE NOT EXISTS (SELECT 1 FROM faq WHERE question = '视频在线但无法播放怎么办？');

INSERT INTO faq (category, question, answer, keywords)
SELECT '数据上传', '数据一直显示未上报怎么办？', '请依次检查前置机上报服务、网络连通性、上报日志和上级接口状态。如仍失败，请提供矿井名称、数据类型、发生时间和错误日志。', '未上报,上传失败,前置机,接口,风险数据'
WHERE NOT EXISTS (SELECT 1 FROM faq WHERE question = '数据一直显示未上报怎么办？');
