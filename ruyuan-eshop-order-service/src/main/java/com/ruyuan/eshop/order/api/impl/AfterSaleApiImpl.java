package com.ruyuan.eshop.order.api.impl;

import com.alibaba.fastjson.JSONObject;
import com.ruyuan.eshop.common.constants.RedisLockKeyConstants;
import com.ruyuan.eshop.common.constants.RocketMqConstant;
import com.ruyuan.eshop.common.core.JsonResult;
import com.ruyuan.eshop.common.enums.CustomerAuditResult;
import com.ruyuan.eshop.common.enums.CustomerAuditSourceEnum;
import com.ruyuan.eshop.common.message.ActualRefundMessage;
import com.ruyuan.eshop.common.redis.RedisLock;
import com.ruyuan.eshop.common.utils.ParamCheckUtil;
import com.ruyuan.eshop.customer.domain.request.CustomerReceiveAfterSaleRequest;
import com.ruyuan.eshop.customer.domain.request.CustomerReviewReturnGoodsRequest;
import com.ruyuan.eshop.order.api.AfterSaleApi;
import com.ruyuan.eshop.order.dao.AfterSaleItemDAO;
import com.ruyuan.eshop.order.dao.AfterSaleRefundDAO;
import com.ruyuan.eshop.order.dao.OrderItemDAO;
import com.ruyuan.eshop.order.domain.dto.CheckLackDTO;
import com.ruyuan.eshop.order.domain.dto.LackDTO;
import com.ruyuan.eshop.order.domain.dto.ReleaseProductStockDTO;
import com.ruyuan.eshop.order.domain.entity.AfterSaleItemDO;
import com.ruyuan.eshop.order.domain.entity.AfterSaleRefundDO;
import com.ruyuan.eshop.order.domain.entity.OrderItemDO;
import com.ruyuan.eshop.order.domain.request.*;
import com.ruyuan.eshop.order.enums.AfterSaleStatusEnum;
import com.ruyuan.eshop.order.exception.OrderBizException;
import com.ruyuan.eshop.order.exception.OrderErrorCodeEnum;
import com.ruyuan.eshop.order.mq.producer.DefaultProducer;
import com.ruyuan.eshop.order.service.OrderAfterSaleService;
import com.ruyuan.eshop.order.service.OrderLackService;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.TransactionListener;
import org.apache.rocketmq.client.producer.TransactionMQProducer;
import org.apache.rocketmq.client.producer.TransactionSendResult;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * ????????????-????????????????????????
 *
 * @author zhonghuashishan
 * @version 1.0
 */
@Slf4j
@DubboService(version = "1.0.0", interfaceClass = AfterSaleApi.class, retries = 0)
public class AfterSaleApiImpl implements AfterSaleApi {

    @Autowired
    private OrderLackService orderLackItemService;

    @Autowired
    private OrderAfterSaleService orderAfterSaleService;

    @Autowired
    private RedisLock redisLock;

    @Autowired
    private AfterSaleItemDAO afterSaleItemDAO;

    @Autowired
    private DefaultProducer defaultProducer;

    @Autowired
    private OrderItemDAO orderItemDAO;

    @Autowired
    private AfterSaleRefundDAO afterSaleRefundDAO;

    /**
     * ????????????/?????????????????????
     */
    @Override
    public JsonResult<Boolean> cancelOrder(CancelOrderRequest cancelOrderRequest) {
        try {
            return orderAfterSaleService.cancelOrder(cancelOrderRequest);
        } catch (OrderBizException e) {
            log.error("biz error", e);
            return JsonResult.buildError(e.getErrorCode(), e.getErrorMsg());
        } catch (Exception e) {
            log.error("system error", e);
            return JsonResult.buildError(e.getMessage());
        }
    }

    @Override
    public JsonResult<LackDTO> lockItem(LackRequest request) {
        log.info("request={}", JSONObject.toJSONString(request));
        try {

            //1?????????????????????
            ParamCheckUtil.checkStringNonEmpty(request.getOrderId(), OrderErrorCodeEnum.ORDER_ID_IS_NULL);
            ParamCheckUtil.checkCollectionNonEmpty(request.getLackItems(), OrderErrorCodeEnum.LACK_ITEM_IS_NULL);

            //2??????????????????
            String lockKey = RedisLockKeyConstants.LACK_REQUEST_KEY + request.getOrderId();
            if (!redisLock.tryLock(lockKey)) {
                throw new OrderBizException(OrderErrorCodeEnum.ORDER_NOT_ALLOW_TO_LACK);
            }
            //3???????????????
            CheckLackDTO checkResult = orderLackItemService.checkRequest(request);
            try {
                //4???????????????
                return JsonResult.buildSuccess(orderLackItemService.executeLackRequest(request, checkResult));
            } finally {
                redisLock.unlock(lockKey);
            }
        } catch (OrderBizException e) {
            log.error("biz error", e);
            return JsonResult.buildError(e.getErrorCode(), e.getErrorMsg());
        } catch (Exception e) {
            log.error("error", e);
            return JsonResult.buildError(e.getMessage());
        }
    }

    @Override
    public JsonResult<Boolean> refundCallback(RefundCallbackRequest payRefundCallbackRequest) {
        String orderId = payRefundCallbackRequest.getOrderId();
        log.info("???????????????????????????????????????,orderId:{}", orderId);
        return orderAfterSaleService.receivePaymentRefundCallback(payRefundCallbackRequest);
    }

    @Override
    public JsonResult<Boolean> receiveCustomerAuditResult(CustomerReviewReturnGoodsRequest customerReviewReturnGoodsRequest) {
        //  1??????????????????????????????????????????
        CustomerAuditAssembleRequest customerAuditAssembleResult = buildCustomerAuditAssembleData(customerReviewReturnGoodsRequest);

        //  2?????????????????????
        if (CustomerAuditResult.REJECT.getCode().equals(customerAuditAssembleResult.getReviewReasonCode())) {
            //  ?????? ???????????? ????????????
            orderAfterSaleService.receiveCustomerAuditReject(customerAuditAssembleResult);
            return JsonResult.buildSuccess(true);
        }
        //  3?????????????????????
        if (CustomerAuditResult.ACCEPT.getCode().equals(customerAuditAssembleResult.getReviewReasonCode())) {
            String orderId = customerAuditAssembleResult.getOrderId();
            Long afterSaleId = customerAuditAssembleResult.getAfterSaleId();
            AfterSaleItemDO afterSaleItemDO = afterSaleItemDAO.getOrderIdAndAfterSaleId(orderId, afterSaleId);
            if (afterSaleItemDO == null) {
                throw new OrderBizException(OrderErrorCodeEnum.AFTER_SALE_ITEM_CANNOT_NULL);
            }
            //  4???????????????????????????
            AuditPassReleaseAssetsRequest auditPassReleaseAssetsRequest = buildAuditPassReleaseAssets(afterSaleItemDO, customerAuditAssembleResult, orderId);
            //  5???????????????MQ??????
            TransactionMQProducer producer = defaultProducer.getProducer();
            producer.setTransactionListener(new TransactionListener() {
                @Override
                public LocalTransactionState executeLocalTransaction(Message msg, Object arg) {
                    try {
                        //  ?????? ???????????? ????????????
                        orderAfterSaleService.receiveCustomerAuditAccept(customerAuditAssembleResult);
                        return LocalTransactionState.COMMIT_MESSAGE;
                    } catch (Exception e) {
                        log.error("system error", e);
                        return LocalTransactionState.ROLLBACK_MESSAGE;
                    }
                }

                @Override
                public LocalTransactionState checkLocalTransaction(MessageExt msg) {
                    Integer customerAuditAfterSaleStatus = orderAfterSaleService
                            .findCustomerAuditAfterSaleStatus(customerAuditAssembleResult.getAfterSaleId());
                    if (AfterSaleStatusEnum.REVIEW_PASS.getCode().equals(customerAuditAfterSaleStatus)) {
                        return LocalTransactionState.COMMIT_MESSAGE;
                    }
                    return LocalTransactionState.ROLLBACK_MESSAGE;
                }
            });

            try {
                Message message = new Message(RocketMqConstant.CUSTOMER_AUDIT_PASS_RELEASE_ASSETS_TOPIC,
                        JSONObject.toJSONString(auditPassReleaseAssetsRequest).getBytes(StandardCharsets.UTF_8));
                // 6???????????????MQ?????? ???????????????????????????????????????
                TransactionSendResult result = producer.sendMessageInTransaction(message, auditPassReleaseAssetsRequest);
                if (!result.getLocalTransactionState().equals(LocalTransactionState.COMMIT_MESSAGE)) {
                    throw new OrderBizException(OrderErrorCodeEnum.SEND_AUDIT_PASS_RELEASE_ASSETS_FAILED);
                }
                return JsonResult.buildSuccess(true);
            } catch (Exception e) {
                throw new OrderBizException(OrderErrorCodeEnum.SEND_TRANSACTION_MQ_FAILED);
            }
        }
        return JsonResult.buildSuccess(true);
    }

    /**
     * ????????????????????????
     *
     * @return
     */
    private AuditPassReleaseAssetsRequest buildAuditPassReleaseAssets(AfterSaleItemDO afterSaleItemDO,
                                                                      CustomerAuditAssembleRequest customerAuditAssembleResult, String orderId) {
        AuditPassReleaseAssetsRequest auditPassReleaseAssetsRequest = new AuditPassReleaseAssetsRequest();

        //  ??????????????????
        ReleaseProductStockDTO releaseProductStockDTO = new ReleaseProductStockDTO();
        List<ReleaseProductStockDTO.OrderItemRequest> orderItemDTOList = new ArrayList<>();
        ReleaseProductStockDTO.OrderItemRequest orderItemRequest = new ReleaseProductStockDTO.OrderItemRequest();

        orderItemRequest.setSkuCode(afterSaleItemDO.getSkuCode());
        orderItemRequest.setSaleQuantity(afterSaleItemDO.getReturnQuantity());
        orderItemDTOList.add(orderItemRequest);
        releaseProductStockDTO.setOrderId(orderId);
        releaseProductStockDTO.setOrderItemRequestList(orderItemDTOList);

        auditPassReleaseAssetsRequest.setReleaseProductStockDTO(releaseProductStockDTO);

        //  ??????????????????
        ActualRefundMessage actualRefundMessage = new ActualRefundMessage();
        actualRefundMessage.setAfterSaleRefundId(customerAuditAssembleResult.getAfterSaleRefundId());
        actualRefundMessage.setOrderId(customerAuditAssembleResult.getOrderId());
        actualRefundMessage.setAfterSaleId(customerAuditAssembleResult.getAfterSaleId());

         /*
            ?????????????????????????????????????????????????????????????????? ???????????????
            ????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
            ?????? ???????????????????????????????????? = ??????????????????????????????????????? + 1 ?????????????????????????????????????????????
            ?????? ??????????????????????????????????????????????????????????????????
         */
        //  ????????????????????????????????????
        Long afterSaleId = customerAuditAssembleResult.getAfterSaleId();
        List<OrderItemDO> orderItemDOList = orderItemDAO.listByOrderId(orderId);
        //  ????????????????????????????????????????????????????????????
        List<AfterSaleItemDO> afterSaleItemDOList = afterSaleItemDAO.listNotContainCurrentAfterSaleId(orderId, afterSaleId);
        //  ?????????????????????????????????????????????????????????
        if (orderItemDOList.size() == afterSaleItemDOList.size() + 1) {
            //  ?????????????????????????????????????????????
            actualRefundMessage.setLastReturnGoods(true);
        }
        auditPassReleaseAssetsRequest.setActualRefundMessage(actualRefundMessage);

        return auditPassReleaseAssetsRequest;
    }

    /**
     * ???????????????????????????????????????
     */
    private CustomerAuditAssembleRequest buildCustomerAuditAssembleData(CustomerReviewReturnGoodsRequest customerReviewReturnGoodsRequest) {
        CustomerAuditAssembleRequest customerAuditAssembleRequest = new CustomerAuditAssembleRequest();

        Long afterSaleId = customerReviewReturnGoodsRequest.getAfterSaleId();
        String orderId = customerReviewReturnGoodsRequest.getOrderId();
        Integer auditResult = customerReviewReturnGoodsRequest.getAuditResult();

        customerAuditAssembleRequest.setAfterSaleId(afterSaleId);
        customerAuditAssembleRequest.setAfterSaleRefundId(customerReviewReturnGoodsRequest.getAfterSaleRefundId());
        customerAuditAssembleRequest.setOrderId(orderId);
        customerAuditAssembleRequest.setReviewTime(new Date());
        customerAuditAssembleRequest.setReviewSource(CustomerAuditSourceEnum.SELF_MALL.getCode());
        customerAuditAssembleRequest.setReviewReasonCode(auditResult);
        customerAuditAssembleRequest.setAuditResultDesc(customerReviewReturnGoodsRequest.getAuditResultDesc());

        return customerAuditAssembleRequest;
    }

    @Override
    public JsonResult<Boolean> revokeAfterSale(RevokeAfterSaleRequest request) {
        //1???????????????
        ParamCheckUtil.checkObjectNonNull(request.getAfterSaleId(), OrderErrorCodeEnum.AFTER_SALE_ID_IS_NULL);

        Long afterSaleId = request.getAfterSaleId();
        String lockKey = RedisLockKeyConstants.REFUND_KEY + afterSaleId;
        //2?????????????????????????????????????????????
        // 2.1 ?????????
        // 2.2 ??????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
        if (!redisLock.tryLock(lockKey)) {
            throw new OrderBizException(OrderErrorCodeEnum.AFTER_SALE_CANNOT_REVOKE);
        }

        try {
            //3???????????????
            orderAfterSaleService.revokeAfterSale(request);
        } finally {
            //4????????????
            redisLock.unlock(lockKey);
        }

        return JsonResult.buildSuccess(true);
    }

    @Override
    public JsonResult<Long> customerFindAfterSaleRefundInfo(CustomerReceiveAfterSaleRequest customerReceiveAfterSaleRequest) {
        String afterSaleId = customerReceiveAfterSaleRequest.getAfterSaleId();
        AfterSaleRefundDO afterSaleRefundDO = afterSaleRefundDAO.findAfterSaleRefundByfterSaleId(afterSaleId);
        if (afterSaleRefundDO == null) {
            throw new OrderBizException(OrderErrorCodeEnum.AFTER_SALE_REFUND_ID_IS_NULL);
        }
        JsonResult<Long> jsonResult = new JsonResult<>();
        jsonResult.setData(afterSaleRefundDO.getId());
        jsonResult.setSuccess(true);
        return jsonResult;
    }

}