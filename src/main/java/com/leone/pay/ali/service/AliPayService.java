package com.leone.pay.ali.service;

import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.domain.AlipayFundTransToaccountTransferModel;
import com.alipay.api.domain.AlipayTradeAppPayModel;
import com.alipay.api.request.*;
import com.alipay.api.response.AlipayFundTransToaccountTransferResponse;
import com.alipay.api.response.AlipayTradeAppPayResponse;
import com.alipay.api.response.AlipayTradePrecreateResponse;
import com.alipay.api.response.AlipayTradeRefundResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leone.pay.common.Result;
import com.leone.pay.common.enums.status.OrderStatus;
import com.leone.pay.common.exception.ExceptionMessage;
import com.leone.pay.common.exception.ValidateException;
import com.leone.pay.common.property.AppProperties;
import com.leone.pay.entity.Order;
import com.leone.pay.entity.OrderBill;
import com.leone.pay.service.OrderBillService;
import com.leone.pay.service.OrderService;
import com.leone.pay.service.UserService;
import com.leone.pay.utils.PaymentUtils;
import com.leone.pay.utils.RandomUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.ObjectUtils;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;

/**
 * <p> 支付宝支付
 *
 * @author leone
 * @since 2018-05-27
 **/
@Slf4j
@Service
public class AliPayService {

    @Resource
    private OrderService orderService;

    @Resource
    private UserService userService;

    @Resource
    private AppProperties appProperties;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private OrderBillService orderBillService;

    // 阿里 sdk 封装
    private AlipayClient alipayClient;

    @PostConstruct
    public void initMethod() {
        alipayClient = new DefaultAlipayClient(
                appProperties.getAli().getServer_url(),
                appProperties.getAli().getApp_id(),
                appProperties.getAli().getAlipay_private_key(),
                appProperties.getAli().getFormat(),
                appProperties.getAli().getCharset(),
                appProperties.getAli().getAlipay_public_key(),
                appProperties.getAli().getSign_type());
    }

    /**
     * 支付宝提现
     *
     * @param orderId
     */
    public Result deposit(Long orderId) {
        // 校验订单信息
        Order order = orderService.findOne(orderId);
        if (order.getStatus() != OrderStatus.CREATE.getStatus()) {
            log.error(ExceptionMessage.ORDER_STATUS_INCORRECTNESS + " orderId: {}", orderId);
            throw new ValidateException(ExceptionMessage.ORDER_STATUS_INCORRECTNESS);
        }

        AlipayFundTransToaccountTransferModel transferModel = new AlipayFundTransToaccountTransferModel();

        transferModel.setOutBizNo(1 + RandomUtil.randomNum(15));
        transferModel.setAmount(order.getTotalFee().toString());
        transferModel.setPayeeAccount("real account");
        transferModel.setPayeeRealName("real name");
        transferModel.setPayerShowName("from name");
        transferModel.setRemark("remark");
        transferModel.setPayeeType("ALIPAY_LOGONID");
        try {
            AlipayFundTransToaccountTransferRequest request = new AlipayFundTransToaccountTransferRequest();
            request.setBizModel(transferModel);
            AlipayFundTransToaccountTransferResponse response = alipayClient.execute(request);
        } catch (AlipayApiException e) {
            log.info("ali deposit error message:{}", e.getMessage());
            return Result.success(ExceptionMessage.ALI_DEPOSIT_SUCCESS);
        }
        return Result.error(ExceptionMessage.ALI_DEPOSIT_FAILED);
    }


    /**
     * 支付宝扫码支付生成二维码响应到浏览器
     *
     * @param orderId
     * @param response
     * @return
     */
    public Result aliQrCodePay(Long orderId, HttpServletResponse response) throws Exception {
        // 校验订单信息
        Order order = orderService.findOne(orderId);
        if (order.getStatus() != OrderStatus.CREATE.getStatus()) {
            log.error(ExceptionMessage.ORDER_STATUS_INCORRECTNESS + " orderId: {}", orderId);
            throw new ValidateException(ExceptionMessage.ORDER_STATUS_INCORRECTNESS);
        }

        AlipayTradePrecreateRequest request = new AlipayTradePrecreateRequest();
        Map<String, String> params = new TreeMap<>();
        params.put("out_trade_no", order.getOutTradeNo());
        params.put("total_amount", order.getTotalFee().toString());
        params.put("subject", "备注");
        params.put("body", "详情");
        params.put("store_id", "NJ_2031");
        params.put("timeout_express", "90m");
        request.setBizContent(objectMapper.writeValueAsString(params));
        request.setNotifyUrl(appProperties.getAli().getNotify_url());

        AlipayTradePrecreateResponse responseData = alipayClient.execute(request);
        log.info("response:{}", responseData.getBody());
        String qrCode = responseData.getQrCode();
        if (!ObjectUtils.isEmpty(qrCode)) {
            PaymentUtils.createQRCode(qrCode, response);
            return Result.success(ExceptionMessage.SUCCESS);
        }
        return Result.error(ExceptionMessage.ALI_PAY_CREATE_QR_CODE_SUCCESS);
    }

    /**
     * 支付宝退款
     *
     * @param orderId
     * @param servletRequest
     * @return
     */
    public Result aliRefund(Long orderId, HttpServletRequest servletRequest) throws Exception {
        // 校验订单信息
        Order order = orderService.findOne(orderId);
        if (order.getStatus() < OrderStatus.PAY.getStatus()) {
            log.error(ExceptionMessage.ORDER_STATUS_INCORRECTNESS + " orderId: {}", orderId);
            throw new ValidateException(ExceptionMessage.ORDER_STATUS_INCORRECTNESS);
        }
        // 创建退款请求builder，设置请求参数
        AlipayTradeRefundRequest request = new AlipayTradeRefundRequest();
        Map<String, String> params = new TreeMap<>();
        // 必须 商户订单号
        params.put("out_trade_no", order.getOutTradeNo());
        // 必须 支付宝交易号
        params.put("trade_no", order.getTotalFee().toString());
        // 必须 退款金额
        params.put("refund_amount", order.getTotalFee().toString());
        // 可选 代表 退款的原因说明
        params.put("refund_reason", "退款的原因说明");
        // 可选 标识一次退款请求，同一笔交易多次退款需要保证唯一（就是out_request_no在2次退款一笔交易时，要不一样），如需部分退款，则此参数必传
        params.put("out_request_no", 1 + RandomUtil.randomNum(11));
        // 可选 代表 商户的门店编号
        params.put("store_id", "90m");
        request.setBizContent(objectMapper.writeValueAsString(params));
        AlipayTradeRefundResponse responseData = alipayClient.execute(request);
        if (responseData.isSuccess()) {
            log.info("ali refund success tradeNo:{}", order.getOutTradeNo());
            return Result.success(ExceptionMessage.SUCCESS);
        }
        log.info("ali refund failed tradeNo:{}", order.getOutTradeNo());
        return Result.error(ExceptionMessage.ALI_PAY_REFUND_FAILED);
    }


    /**
     * 阿里pc支付
     *
     * @param orderId
     * @param servletRequest
     * @return
     */
    public String aliPcPay(Long orderId, HttpServletRequest servletRequest) throws Exception {
        Order order = orderService.findOne(orderId);
        AlipayTradePagePayRequest payRequest = new AlipayTradePagePayRequest();
        // 前台通知
        payRequest.setReturnUrl(appProperties.getAli().getReturn_url());
        // 后台回调
        payRequest.setNotifyUrl(appProperties.getAli().getNotify_url());
        Map<String, String> params = new TreeMap<>();
        params.put("out_trade_no", order.getOutTradeNo());
        // 订单金额:元
        params.put("total_amount", order.getTotalFee().toString());
        params.put("subject", "订单标题");
        // 实际收款账号，一般填写商户PID即可
        params.put("seller_id", appProperties.getAli().getMch_id());
        // 电脑网站支付
        params.put("product_code", "FAST_INSTANT_TRADE_PAY");
        params.put("body", "两个橘子");
        payRequest.setBizContent(objectMapper.writeValueAsString(params));
        log.info("业务参数:" + payRequest.getBizContent());
        String result = ExceptionMessage.ERROR.getMessage();
        try {
            result = alipayClient.pageExecute(payRequest).getBody();
        } catch (AlipayApiException e) {
            log.error("ali pay error message:{}", e.getMessage());
        }
        return result;
    }

    /**
     * 支付宝App支付
     *
     * @param orderId
     * @param servletRequest
     * @return
     */
    public String appPay(Long orderId, HttpServletRequest servletRequest) {
        Order order = orderService.findOne(orderId);
        AlipayTradeAppPayRequest request = new AlipayTradeAppPayRequest();
        // SDK已经封装掉了公共参数，这里只需要传入业务参数。以下方法为sdk的model入参方式(model和biz_content同时存在的情况下取biz_content)。
        AlipayTradeAppPayModel model = new AlipayTradeAppPayModel();
        model.setBody("描述");
        model.setSubject("商品名称");
        model.setOutTradeNo(order.getOutTradeNo());
        model.setTimeoutExpress("30m");
        model.setTotalAmount(order.getTotalFee().toString());
        model.setProductCode("QUICK_MSECURITY_PAY");
        request.setBizModel(model);
        request.setNotifyUrl(appProperties.getAli().getNotify_url());
        try {
            // 这里和普通的接口调用不同，使用的是sdkExecute
            AlipayTradeAppPayResponse response = alipayClient.sdkExecute(request);
            //就是orderString 可以直接给客户端请求，无需再做处理。
            log.info("orderString:{}", response.getBody());
            return response.getBody();
        } catch (AlipayApiException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 支付回调通知
     *
     * @param request
     * @param response
     */
    public void aliNotify(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String requestXml = PaymentUtils.getRequestData(request);
        Map requestMap = PaymentUtils.xmlToMap(requestXml);
        Assert.notNull(requestMap, ExceptionMessage.XML_DATA_INCORRECTNESS.getMessage());

        // 当返回的return_code为SUCCESS则回调成功
        if (requestMap.get("code").equals(10000)) {

            // TODO 保存订单流水 具体细节待实现
            orderBillService.save(new OrderBill());
            log.info("notify success");
        } else {
            log.error("notify failed");
        }
    }
}
