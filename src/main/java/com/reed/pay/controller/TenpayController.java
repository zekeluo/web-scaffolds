package com.reed.pay.controller;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.reed.pay.tenpay.AccessTokenUtil;
import com.reed.pay.tenpay.RequestHandler;
import com.reed.pay.tenpay.ResponseHandler;
import com.reed.pay.tenpay.TenpayConfig;
import com.reed.pay.tenpay.client.ClientResponseHandler;
import com.reed.pay.tenpay.client.TenpayHttpClient;
import com.reed.pay.tenpay.util.Sha1Util;

/**
 * 微信支付服务器异步通知
 * 
 */
@RestController
@RequestMapping("/tenpay")
public abstract class TenpayController extends AbstractPayController {
	private static final Logger LOGGER = LoggerFactory.getLogger("PAY");
	@Autowired
	private AccessTokenUtil accessTokenUtil;

	/**
	 * 微信支付回调接口
	 * 
	 * @param request
	 * @return
	 * @throws UnsupportedEncodingException
	 * @throws IOException
	 */
	@RequestMapping(value = "/notify", method = RequestMethod.POST)
	public String notify(@Valid TenpayNotifyFormBean formBean,
			HttpServletRequest request, HttpServletResponse response)
			throws IOException {

		ResponseHandler resHandler = new ResponseHandler(request, response);
		resHandler.setKey(TenpayConfig.partner_key);
		// 创建请求对象
		RequestHandler queryReq = new RequestHandler(request, response);
		// queryReq.init();
		if (resHandler.isTenpaySign() == true) {
			// 商户订单号
			String out_trade_no = resHandler.getParameter("out_trade_no");
			// 财付通订单号
			String transaction_id = resHandler.getParameter("transaction_id");
			// 金额,以分为单位
			String total_fee = resHandler.getParameter("total_fee");
			// 如果有使用折扣券，discount有值，total_fee+discount=原请求的total_fee
			String discount = resHandler.getParameter("discount");
			// 支付结果
			String trade_state = resHandler.getParameter("trade_state");
			// 判断签名及结果
			if ("0".equals(trade_state)) {
				InputStream is = request.getInputStream();
				BufferedReader br = new BufferedReader(new InputStreamReader(
						is, "UTF-8"));
				String buffer = null;
				StringBuffer sb = new StringBuffer();
				while ((buffer = br.readLine()) != null) {
					sb.append(buffer);
				}
				String notifyMessage = sb.toString();
				LOGGER.info("支付|notifyMessage=" + notifyMessage);
				resHandler.doParse(notifyMessage);
				String openId = resHandler.getParameter("OpenId");
				// 即时到账处理业务开始
				String orderNo = formBean.getOut_trade_no();
				// TODO
				// 处理数据库逻辑
				// 注意交易单不要重复处理
				// 注意判断返回金额
				// 即时到账处理业务完毕
				if (!payDoneBusiness(orderNo)) {
					return "fail";
				}
			} else {
				LOGGER.error(String.format("微信支付异步回调|即时到账支付失败，订单号：%s,交易号：%s",
						formBean.getOut_trade_no(),
						formBean.getTransaction_id()));
				return "fail";
			}
			return "success";
		} else {
			LOGGER.error(String.format("微信支付异步回调|通知签名验证失败，订单号：%s,交易号：%s",
					formBean.getOut_trade_no(), formBean.getTransaction_id()));
			return "fail";
		}
	}

	/**
	 * 生成tenpay的订单支付信息
	 * 
	 * @param orderNo
	 *            订单号
	 * @param price
	 *            订单金额
	 * @param procductNames
	 *            商品名集合
	 * @param request
	 * @param response
	 * @return
	 * @throws UnsupportedEncodingException
	 */
	public TenpayPayVo generateOrderInfoOfTenpay(String orderNo, String price,
			List<String> procductNames, HttpServletRequest request,
			HttpServletResponse response) throws UnsupportedEncodingException {
		String out_trade_no = orderNo;
		// 获取提交的商品价格(微信订单金额按分计算)
		String order_price = new BigDecimal(price).multiply(
				new BigDecimal("100")).setScale(0)
				+ "";
		// 获取提交的商品名称
		StringBuffer stringBuffer = new StringBuffer("");
		if (CollectionUtils.isNotEmpty(procductNames)) {
			for (String d : procductNames) {
				if (StringUtils.isNotEmpty(d)) {
					stringBuffer.append(d);
				}
			}
		}

		String product_name = StringUtils.abbreviate(stringBuffer.toString(),
				128);
		// String product_name = request.getParameter("product_name");
		TenpayPayVo tenpayPayVo = new TenpayPayVo();
		RequestHandler reqHandler = new RequestHandler(request, response);
		reqHandler.setKey(TenpayConfig.partner_key);
		reqHandler.setGateUrl(TenpayConfig.prepay_url);
		// 获取token值
		String token = accessTokenUtil.getAccessToken();
		if (StringUtils.trimToNull(token) != null) {
			// 生成预支付单
			// 设置package订单参数
			SortedMap<String, String> packageParams = new TreeMap<String, String>();
			packageParams.put("bank_type", TenpayConfig.bank_type); // 银行通道类型
			packageParams.put("body", product_name); // 商品描述
			packageParams.put("notify_url", TenpayConfig.notify_url); // 接收财付通通知的URL
			packageParams.put("partner", TenpayConfig.partner); // 商户号
			packageParams.put("out_trade_no", out_trade_no); // 商家订单号
			packageParams.put("total_fee", order_price); // 商品金额,以分为单位
			packageParams.put("spbill_create_ip", request.getRemoteAddr()); // 订单生成的机器IP，指用户浏览器端IP
			packageParams.put("fee_type", TenpayConfig.fee_type); // 币种，1人民币
			packageParams.put("input_charset", TenpayConfig.input_charset); // 字符编码

			// 获取package包
			String packageValue = reqHandler.genPackage(packageParams);

			String noncestr = Sha1Util.getNonceStr();
			String timestamp = Sha1Util.getTimeStamp();
			String traceid = timestamp;

			// 设置支付参数
			SortedMap<String, String> signParams = new TreeMap<String, String>();
			signParams.put("appid", TenpayConfig.app_id);
			signParams.put("appkey", TenpayConfig.app_key);
			signParams.put("noncestr", noncestr);
			signParams.put("package", packageValue);
			signParams.put("timestamp", timestamp);
			signParams.put("traceid", traceid);

			// 生成支付签名，要采用URLENCODER的原始值进行SHA1算法！
			String sign = Sha1Util.createSHA1Sign(signParams);
			// 增加非参与签名的额外参数
			signParams.put("app_signature", sign);
			signParams.put("sign_method", TenpayConfig.sign_method);

			// 获取prepayId
			String prepayid = reqHandler.sendPrepay(signParams, token);

			if (null != prepayid && !"".equals(prepayid)) {
				// 签名参数列表
				SortedMap<String, String> prePayParams = new TreeMap<String, String>();
				prePayParams.put("appid", TenpayConfig.app_id);
				prePayParams.put("appkey", TenpayConfig.app_key);
				prePayParams.put("noncestr", noncestr);
				prePayParams.put("package", TenpayConfig._package);
				prePayParams.put("partnerid", TenpayConfig.partner);
				prePayParams.put("prepayid", prepayid);
				prePayParams.put("timestamp", timestamp);
				// 生成签名
				sign = Sha1Util.createSHA1Sign(prePayParams);

				// 输出参数
				tenpayPayVo.setRetCode("0");
				tenpayPayVo.setRetMsg("OK");
				TenpayPayInfoVo payInfoVo = new TenpayPayInfoVo();
				payInfoVo.setAppId(TenpayConfig.app_id);
				payInfoVo.setPartnerId(TenpayConfig.partner);
				payInfoVo.setNonceStr(noncestr);
				payInfoVo.setPackageValue(TenpayConfig._package);
				payInfoVo.setPrepayId(prepayid);
				payInfoVo.setTimeStamp(timestamp);
				payInfoVo.setSign(sign);
				tenpayPayVo.setPayInfo(payInfoVo);
				// 测试帐号多个app测试，需要判断Token是否失效，否则重新获取一次
				// if(reqHandler.getLasterrCode()=="40001"){
				// token = reqHandler.getTokenReal();
				// }
			} else {
				tenpayPayVo.setRetCode("-2");
				tenpayPayVo.setRetMsg("错误：获取prepayId失败");
			}
		} else {
			tenpayPayVo.setRetCode("-1");
			tenpayPayVo.setRetMsg("错误：获取不到Token");
		}
		return tenpayPayVo;
	}

	/**
	 * 微信：主动查询订单状态
	 * 
	 * @param orderNo
	 * @param request
	 * @param response
	 * @return 是否支付成功
	 * @throws Exception
	 */
	public boolean orderQueryOfTenpay(String orderNo,
			HttpServletRequest request, HttpServletResponse response)
			throws Exception {
		RequestHandler reqHandler = new RequestHandler(request, response);
		reqHandler.setGateUrl(TenpayConfig.order_query_url);
		// 获取token值
		String token = accessTokenUtil.getAccessToken();
		if (StringUtils.trimToNull(token) != null) {
			// 设置package订单参数
			SortedMap<String, String> packageParams = new TreeMap<String, String>();
			packageParams.put("out_trade_no", orderNo); // 商家订单号
			packageParams.put("partner", TenpayConfig.partner); // 商户号
			// 获取package包
			String packageValue = reqHandler.genPackage(packageParams);
			String timestamp = Sha1Util.getTimeStamp();
			// 设置支付参数
			SortedMap<String, String> signParams = new TreeMap<String, String>();
			signParams.put("appid", TenpayConfig.app_id);
			signParams.put("appkey", TenpayConfig.app_key);
			signParams.put("package", packageValue);
			signParams.put("timestamp", timestamp);

			// 生成支付签名，要采用URLENCODER的原始值进行SHA1算法！
			String sign = Sha1Util.createSHA1Sign(signParams);
			// 增加非参与签名的额外参数
			signParams.put("app_signature", sign);
			signParams.put("sign_method", TenpayConfig.sign_method);
			// 解析响应，判断错误码
			String code = reqHandler.orderQuery(signParams, token);
			if ("0".equals(code)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 查询微信的退款处理状态
	 * 
	 * @param transactionId
	 *            微信交易流水号
	 * @return
	 * @throws Exception
	 */
	public String normalRefundQuery(String transactionId) throws Exception {
		String out_refund_no = null;
		RequestHandler reqHandler = new RequestHandler(null, null);
		TenpayHttpClient httpClient = new TenpayHttpClient();
		ClientResponseHandler resHandler = new ClientResponseHandler();
		// 设置请求参数
		reqHandler.init();
		reqHandler.setCharset(TenpayConfig.input_charset);
		reqHandler.setKey(TenpayConfig.partner_key);
		reqHandler
				.setGateUrl("https://gw.tenpay.com/gateway/normalrefundquery.xml");
		// 设置接口参数
		// -----------------------------
		reqHandler.setParameter("partner", TenpayConfig.partner);
		// reqHandler.setParameter("out_trade_no", "XXX");
		reqHandler.setParameter("transaction_id", transactionId);
		// reqHandler.setParameter("out_refund_no", "XXX");
		// reqHandler.setParameter("refund_id", "XXX");
		// 设置请求返回的等待时间
		// httpClient.setTimeOut(5);
		// 设置发送类型POST
		httpClient.setMethod("POST");
		// 设置请求内容
		String requestUrl = reqHandler.getRequestURL();
		httpClient.setReqContent(requestUrl);
		String rescontent = "null";
		// 后台调用
		if (httpClient.call()) {
			// 设置结果参数
			rescontent = httpClient.getResContent();
			resHandler.setContent(rescontent);
			resHandler.setKey(TenpayConfig.partner_key);
			// 获取返回参数
			String retcode = resHandler.getParameter("retcode");
			// 判断签名及结果
			// 只有签名正确并且retcode为0才是请求成功
			if (resHandler.isTenpaySign() && "0".equals(retcode)) {
				// 取结果参数做业务处理
				// 退款笔数
				String refund_count = resHandler.getParameter("refund_count");

				int count = Integer.parseInt(refund_count);
				// 每笔退款详情
				/*
				 * 退款状态 refund_status 4，10：退款成功。 3，5，6：退款失败。 8，9，11:退款处理中。 1，2:
				 * 未确定，需要商户原退款单号重新发起。
				 * 7：转入代发，退款到银行发现用户的卡作废或者冻结了，导致原路退款银行卡失败，资金回流到商户的现金帐号
				 * ，需要商户人工干预，通过线下或者财付通转账的方式进行退款。
				 */
				for (int i = 0; i < count; i++) {
					String refund_state_n = "refund_state_"
							+ Integer.toString(i);
					String out_refund_no_n = "out_refund_no_"
							+ Integer.toString(i);
					String refund_fee_n = "refund_fee_" + Integer.toString(i);
					String refund_status = resHandler
							.getParameter(refund_state_n);
					if ("4".equals(refund_status) || "10".equals(refund_status)) {
						out_refund_no = resHandler
								.getParameter(out_refund_no_n);
					}
				}
			} else {
				// 错误时，返回结果未签名，记录retcode、retmsg看失败详情。
				LOGGER.info("验证签名失败或业务错误");
				LOGGER.info("retcode:" + resHandler.getParameter("retcode")
						+ " retmsg:" + resHandler.getParameter("retmsg"));
			}
		} else {
			LOGGER.error("后台调用通信失败");
			// 有可能因为网络原因，请求已经处理，但未收到应答。
		}

		// 获取debug信息,建议把请求、应答内容、debug信息，通信返回码写入日志，方便定位问题
		LOGGER.info("http res:" + httpClient.getResponseCode() + ","
				+ httpClient.getErrInfo());
		LOGGER.info("req url:" + requestUrl);
		LOGGER.info("res content:" + rescontent);
		return out_refund_no;
	}
}
