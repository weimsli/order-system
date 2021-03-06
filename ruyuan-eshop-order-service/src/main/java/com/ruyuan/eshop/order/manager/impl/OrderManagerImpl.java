package com.ruyuan.eshop.order.manager.impl;

import com.ruyuan.eshop.address.domain.dto.AddressDTO;
import com.ruyuan.eshop.address.domain.query.AddressQuery;
import com.ruyuan.eshop.common.enums.AmountTypeEnum;
import com.ruyuan.eshop.common.enums.OrderOperateTypeEnum;
import com.ruyuan.eshop.common.enums.OrderStatusEnum;
import com.ruyuan.eshop.common.utils.JsonUtil;
import com.ruyuan.eshop.common.utils.LoggerFormat;
import com.ruyuan.eshop.inventory.domain.request.DeductProductStockRequest;
import com.ruyuan.eshop.market.domain.dto.CalculateOrderAmountDTO;
import com.ruyuan.eshop.market.domain.dto.UserCouponDTO;
import com.ruyuan.eshop.market.domain.query.UserCouponQuery;
import com.ruyuan.eshop.market.domain.request.LockUserCouponRequest;
import com.ruyuan.eshop.order.builder.FullOrderData;
import com.ruyuan.eshop.order.builder.NewOrderBuilder;
import com.ruyuan.eshop.order.config.OrderProperties;
import com.ruyuan.eshop.order.converter.OrderConverter;
import com.ruyuan.eshop.order.dao.*;
import com.ruyuan.eshop.order.domain.entity.*;
import com.ruyuan.eshop.order.domain.request.CreateOrderRequest;
import com.ruyuan.eshop.order.domain.request.PayCallbackRequest;
import com.ruyuan.eshop.order.enums.OrderNoTypeEnum;
import com.ruyuan.eshop.order.enums.PayStatusEnum;
import com.ruyuan.eshop.order.enums.SnapshotTypeEnum;
import com.ruyuan.eshop.order.manager.OrderManager;
import com.ruyuan.eshop.order.manager.OrderNoManager;
import com.ruyuan.eshop.order.remote.AddressRemote;
import com.ruyuan.eshop.order.remote.InventoryRemote;
import com.ruyuan.eshop.order.remote.MarketRemote;
import com.ruyuan.eshop.order.service.impl.NewOrderDataHolder;
import com.ruyuan.eshop.product.domain.dto.ProductSkuDTO;
import io.seata.spring.annotation.GlobalTransactional;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @author zhonghuashishan
 * @version 1.0
 */
@Service
@Slf4j
public class OrderManagerImpl implements OrderManager {

    @Autowired
    private OrderInfoDAO orderInfoDAO;

    @Autowired
    private OrderItemDAO orderItemDAO;

    @Autowired
    private OrderPaymentDetailDAO orderPaymentDetailDAO;

    @Autowired
    private OrderOperateLogDAO orderOperateLogDAO;

    @Autowired
    private OrderAmountDAO orderAmountDAO;

    @Autowired
    private OrderAmountDetailDAO orderAmountDetailDAO;

    @Autowired
    private OrderDeliveryDetailDAO orderDeliveryDetailDAO;

    @Autowired
    private OrderSnapshotDAO orderSnapshotDAO;

    @Autowired
    private OrderProperties orderProperties;

    /**
     * ????????????
     */
    @Autowired
    private MarketRemote marketRemote;

    /**
     * ????????????
     */
    @Autowired
    private AddressRemote addressRemote;

    @Autowired
    private OrderNoManager orderNoManager;

    /**
     * ????????????
     */
    @Autowired
    private InventoryRemote inventoryRemote;

    @Autowired
    private OrderConverter orderConverter;

    /**
     * ??????????????????????????????
     *
     * @param payCallbackRequest
     * @param orderInfoDO
     * @param orderPaymentDetailDO
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void updateOrderStatusPaid(PayCallbackRequest payCallbackRequest, OrderInfoDO orderInfoDO, OrderPaymentDetailDO orderPaymentDetailDO) {

        // ????????????
        String orderId = payCallbackRequest.getOrderId();
        Integer preOrderStatus = orderInfoDO.getOrderStatus();
        orderInfoDO.setOrderStatus(OrderStatusEnum.PAID.getCode());
        orderInfoDAO.updateById(orderInfoDO);

        // ??????????????????
        orderPaymentDetailDO.setPayStatus(PayStatusEnum.PAID.getCode());
        orderPaymentDetailDAO.updateById(orderPaymentDetailDO);

        // ??????????????????????????????
        OrderOperateLogDO orderOperateLogDO = new OrderOperateLogDO();
        orderOperateLogDO.setOrderId(orderId);
        orderOperateLogDO.setOperateType(OrderOperateTypeEnum.PAID_ORDER.getCode());
        orderOperateLogDO.setPreStatus(preOrderStatus);
        orderOperateLogDO.setCurrentStatus(orderInfoDO.getOrderStatus());
        orderOperateLogDO.setRemark("????????????????????????"
                + orderOperateLogDO.getPreStatus() + "-"
                + orderOperateLogDO.getCurrentStatus());
        orderOperateLogDAO.save(orderOperateLogDO);

        // ???????????????????????????
        List<OrderInfoDO> subOrderInfoDOList = orderInfoDAO.listByParentOrderId(orderId);
        if (subOrderInfoDOList != null && !subOrderInfoDOList.isEmpty()) {
            // ??????????????????????????????????????????
            Integer newPreOrderStatus = orderInfoDO.getOrderStatus();
            orderInfoDO.setOrderStatus(OrderStatusEnum.INVALID.getCode());
            orderInfoDAO.updateById(orderInfoDO);

            // ??????????????????????????????
            OrderOperateLogDO newOrderOperateLogDO = new OrderOperateLogDO();
            newOrderOperateLogDO.setOrderId(orderId);
            newOrderOperateLogDO.setOperateType(OrderOperateTypeEnum.PAID_ORDER.getCode());
            newOrderOperateLogDO.setPreStatus(newPreOrderStatus);
            newOrderOperateLogDO.setCurrentStatus(OrderStatusEnum.INVALID.getCode());
            orderOperateLogDO.setRemark("????????????????????????????????????????????????"
                    + newOrderOperateLogDO.getPreStatus() + "-"
                    + newOrderOperateLogDO.getCurrentStatus());
            orderOperateLogDAO.save(newOrderOperateLogDO);

            // ???????????????????????????
            List<OrderInfoDO> tempSubOrderInfoDOList = new ArrayList<>();
            List<String> tempSubOrderIdList = new ArrayList<>();
            List<OrderOperateLogDO> tempSubOrderOperateLogDOList = new ArrayList<>();
            for (OrderInfoDO subOrderInfo : subOrderInfoDOList) {
                Integer subPreOrderStatus = subOrderInfo.getOrderStatus();
                subOrderInfo.setOrderStatus(OrderStatusEnum.PAID.getCode());
                tempSubOrderInfoDOList.add(subOrderInfo);

                // ????????????????????????
                String subOrderId = subOrderInfo.getOrderId();
                tempSubOrderIdList.add(subOrderId);

                // ????????????????????????
                OrderOperateLogDO subOrderOperateLogDO = new OrderOperateLogDO();
                subOrderOperateLogDO.setOrderId(subOrderId);
                subOrderOperateLogDO.setOperateType(OrderOperateTypeEnum.PAID_ORDER.getCode());
                subOrderOperateLogDO.setPreStatus(subPreOrderStatus);
                subOrderOperateLogDO.setCurrentStatus(OrderStatusEnum.PAID.getCode());
                orderOperateLogDO.setRemark("????????????????????????????????????????????????"
                        + subOrderOperateLogDO.getPreStatus() + "-"
                        + subOrderOperateLogDO.getCurrentStatus());
                tempSubOrderOperateLogDOList.add(subOrderOperateLogDO);
            }

            // ???????????????
            if (!tempSubOrderInfoDOList.isEmpty()) {
                orderInfoDAO.updateBatchById(tempSubOrderInfoDOList);
            }

            // ??????????????????????????????
            if (!tempSubOrderIdList.isEmpty()) {
                OrderPaymentDetailDO subOrderPaymentDetailDO = new OrderPaymentDetailDO();
                subOrderPaymentDetailDO.setPayStatus(PayStatusEnum.PAID.getCode());
                orderPaymentDetailDAO.updateBatchByOrderIds(subOrderPaymentDetailDO, tempSubOrderIdList);
            }

            // ??????????????????????????????
            if (!tempSubOrderOperateLogDOList.isEmpty()) {
                orderOperateLogDAO.saveBatch(tempSubOrderOperateLogDOList);
            }
        }

    }

    /**
     * ????????????
     *
     * @param createOrderRequest
     * @param productSkuList
     * @param calculateOrderAmountDTO
     */
    @Override
    @GlobalTransactional(rollbackFor = Exception.class)
    public void createOrder(CreateOrderRequest createOrderRequest, List<ProductSkuDTO> productSkuList, CalculateOrderAmountDTO calculateOrderAmountDTO) {
        // ???????????????
        lockUserCoupon(createOrderRequest);

        // ????????????
        deductProductStock(createOrderRequest);

        // ????????????????????????
        addNewOrder(createOrderRequest, productSkuList, calculateOrderAmountDTO);
    }


    /**
     * ?????????????????????
     */
    private void lockUserCoupon(CreateOrderRequest createOrderRequest) {
        String couponId = createOrderRequest.getCouponId();
        if (StringUtils.isEmpty(couponId)) {
            return;
        }
        LockUserCouponRequest lockUserCouponRequest = orderConverter.convertLockUserCouponRequest(createOrderRequest);
        // ???????????????????????????????????????
        marketRemote.lockUserCoupon(lockUserCouponRequest);
    }

    /**
     * ??????????????????
     *
     * @param createOrderRequest ????????????
     */
    private void deductProductStock(CreateOrderRequest createOrderRequest) {
        String orderId = createOrderRequest.getOrderId();
        List<DeductProductStockRequest.OrderItemRequest> orderItemRequestList =
                orderConverter.convertOrderItemRequest(createOrderRequest.getOrderItemRequestList());
        DeductProductStockRequest lockProductStockRequest = new DeductProductStockRequest();
        lockProductStockRequest.setOrderId(orderId);
        lockProductStockRequest.setOrderItemRequestList(orderItemRequestList);
        inventoryRemote.deductProductStock(lockProductStockRequest);
    }

    /**
     * ??????????????????????????????
     */
    private void addNewOrder(CreateOrderRequest createOrderRequest, List<ProductSkuDTO> productSkuList, CalculateOrderAmountDTO calculateOrderAmountDTO) {
        String orderId = createOrderRequest.getOrderId();
        // ?????????????????????
        NewOrderDataHolder newOrderDataHolder = new NewOrderDataHolder();

        // ???????????????
        FullOrderData fullMasterOrderData = addNewMasterOrder(createOrderRequest, productSkuList, calculateOrderAmountDTO);

        // ????????????????????????NewOrderData?????????
        newOrderDataHolder.appendOrderData(fullMasterOrderData);


        // ??????????????????????????????????????????????????????????????????
        Map<Integer, List<ProductSkuDTO>> productTypeMap = productSkuList.stream().collect(Collectors.groupingBy(ProductSkuDTO::getProductType));
        if (productTypeMap.keySet().size() > 1) {
            for (Integer productType : productTypeMap.keySet()) {
                // ???????????????
                FullOrderData fullSubOrderData = addNewSubOrder(fullMasterOrderData, productType);

                // ????????????????????????NewOrderData?????????
                newOrderDataHolder.appendOrderData(fullSubOrderData);
            }
        }

        // ????????????????????????
        // ????????????
        List<OrderInfoDO> orderInfoDOList = newOrderDataHolder.getOrderInfoDOList();
        if (!orderInfoDOList.isEmpty()) {
            log.info(LoggerFormat.build()
                    .remark("??????????????????")
                    .data("orderId", orderId)
                    .finish());
            orderInfoDAO.saveBatch(orderInfoDOList);
        }

        // ????????????
        List<OrderItemDO> orderItemDOList = newOrderDataHolder.getOrderItemDOList();
        if (!orderItemDOList.isEmpty()) {
            log.info(LoggerFormat.build()
                    .remark("??????????????????")
                    .data("orderId", orderId)
                    .finish());
            orderItemDAO.saveBatch(orderItemDOList);
        }

        // ??????????????????
        List<OrderDeliveryDetailDO> orderDeliveryDetailDOList = newOrderDataHolder.getOrderDeliveryDetailDOList();
        if (!orderDeliveryDetailDOList.isEmpty()) {
            log.info(LoggerFormat.build()
                    .remark("????????????????????????")
                    .data("orderId", orderId)
                    .finish());
            orderDeliveryDetailDAO.saveBatch(orderDeliveryDetailDOList);
        }

        // ??????????????????
        List<OrderPaymentDetailDO> orderPaymentDetailDOList = newOrderDataHolder.getOrderPaymentDetailDOList();
        if (!orderPaymentDetailDOList.isEmpty()) {
            log.info(LoggerFormat.build()
                    .remark("????????????????????????")
                    .data("orderId", orderId)
                    .finish());
            orderPaymentDetailDAO.saveBatch(orderPaymentDetailDOList);
        }

        // ??????????????????
        List<OrderAmountDO> orderAmountDOList = newOrderDataHolder.getOrderAmountDOList();
        if (!orderAmountDOList.isEmpty()) {
            log.info(LoggerFormat.build()
                    .remark("????????????????????????")
                    .data("orderId", orderId)
                    .finish());
            orderAmountDAO.saveBatch(orderAmountDOList);
        }

        // ??????????????????
        List<OrderAmountDetailDO> orderAmountDetailDOList = newOrderDataHolder.getOrderAmountDetailDOList();
        if (!orderAmountDetailDOList.isEmpty()) {
            log.info(LoggerFormat.build()
                    .remark("????????????????????????")
                    .data("orderId", orderId)
                    .finish());
            orderAmountDetailDAO.saveBatch(orderAmountDetailDOList);
        }

        // ??????????????????????????????
        List<OrderOperateLogDO> orderOperateLogDOList = newOrderDataHolder.getOrderOperateLogDOList();
        if (!orderOperateLogDOList.isEmpty()) {
            log.info(LoggerFormat.build()
                    .remark("????????????????????????????????????")
                    .data("orderId", orderId)
                    .finish());
            orderOperateLogDAO.saveBatch(orderOperateLogDOList);
        }

        // ??????????????????
        List<OrderSnapshotDO> orderSnapshotDOList = newOrderDataHolder.getOrderSnapshotDOList();
        if (!orderSnapshotDOList.isEmpty()) {
            log.info(LoggerFormat.build()
                    .remark("????????????????????????")
                    .data("orderId", orderId)
                    .finish());
            orderSnapshotDAO.saveBatch(orderSnapshotDOList);
        }
    }

    /**
     * ???????????????????????????
     */
    private FullOrderData addNewMasterOrder(CreateOrderRequest createOrderRequest, List<ProductSkuDTO> productSkuList,
                                            CalculateOrderAmountDTO calculateOrderAmountDTO) {
        NewOrderBuilder newOrderBuilder = new NewOrderBuilder(createOrderRequest, productSkuList,
                calculateOrderAmountDTO, orderProperties, orderConverter);
        FullOrderData fullOrderData = newOrderBuilder.buildOrder()
                .buildOrderItems()
                .buildOrderDeliveryDetail()
                .buildOrderPaymentDetail()
                .buildOrderAmount()
                .buildOrderAmountDetail()
                .buildOperateLog()
                .buildOrderSnapshot()
                .build();

        // ????????????
        OrderInfoDO orderInfoDO = fullOrderData.getOrderInfoDO();

        // ??????????????????
        List<OrderItemDO> orderItemDOList = fullOrderData.getOrderItemDOList();

        // ??????????????????
        List<OrderAmountDO> orderAmountDOList = fullOrderData.getOrderAmountDOList();

        // ??????????????????
        OrderDeliveryDetailDO orderDeliveryDetailDO = fullOrderData.getOrderDeliveryDetailDO();
        String detailAddress = getDetailAddress(orderDeliveryDetailDO);
        orderDeliveryDetailDO.setDetailAddress(detailAddress);

        // ??????????????????????????????
        OrderOperateLogDO orderOperateLogDO = fullOrderData.getOrderOperateLogDO();
        String remark = "??????????????????0-10";
        orderOperateLogDO.setRemark(remark);

        // ??????????????????????????????
        List<OrderSnapshotDO> orderSnapshotDOList = fullOrderData.getOrderSnapshotDOList();
        for (OrderSnapshotDO orderSnapshotDO : orderSnapshotDOList) {
            // ???????????????
            if (orderSnapshotDO.getSnapshotType().equals(SnapshotTypeEnum.ORDER_COUPON.getCode())) {
                String couponId = orderInfoDO.getCouponId();
                String userId = orderInfoDO.getUserId();
                UserCouponQuery userCouponQuery = new UserCouponQuery();
                userCouponQuery.setCouponId(couponId);
                userCouponQuery.setUserId(userId);
                UserCouponDTO userCouponDTO = marketRemote.getUserCoupon(userCouponQuery);
                if (userCouponDTO != null) {
                    orderSnapshotDO.setSnapshotJson(JsonUtil.object2Json(userCouponDTO));
                } else {
                    orderSnapshotDO.setSnapshotJson(JsonUtil.object2Json(couponId));
                }
            }
            // ??????????????????
            else if (orderSnapshotDO.getSnapshotType().equals(SnapshotTypeEnum.ORDER_AMOUNT.getCode())) {
                orderSnapshotDO.setSnapshotJson(JsonUtil.object2Json(orderAmountDOList));
            }
            // ??????????????????
            else if (orderSnapshotDO.getSnapshotType().equals(SnapshotTypeEnum.ORDER_ITEM.getCode())) {
                orderSnapshotDO.setSnapshotJson(JsonUtil.object2Json(orderItemDOList));
            }
        }

        return fullOrderData;
    }

    /**
     * ??????????????????????????????
     */
    private String getDetailAddress(OrderDeliveryDetailDO orderDeliveryDetailDO) {
        String provinceCode = orderDeliveryDetailDO.getProvince();
        String cityCode = orderDeliveryDetailDO.getCity();
        String areaCode = orderDeliveryDetailDO.getArea();
        String streetCode = orderDeliveryDetailDO.getStreet();
        AddressQuery query = new AddressQuery();
        query.setProvinceCode(provinceCode);
        query.setCityCode(cityCode);
        query.setAreaCode(areaCode);
        query.setStreetCode(streetCode);
        AddressDTO addressDTO = addressRemote.queryAddress(query);
        if (addressDTO == null) {
            return orderDeliveryDetailDO.getDetailAddress();
        }

        StringBuilder detailAddress = new StringBuilder();
        if (StringUtils.isNotEmpty(addressDTO.getProvince())) {
            detailAddress.append(addressDTO.getProvince());
        }
        if (StringUtils.isNotEmpty(addressDTO.getCity())) {
            detailAddress.append(addressDTO.getCity());
        }
        if (StringUtils.isNotEmpty(addressDTO.getArea())) {
            detailAddress.append(addressDTO.getArea());
        }
        if (StringUtils.isNotEmpty(addressDTO.getStreet())) {
            detailAddress.append(addressDTO.getStreet());
        }
        if (StringUtils.isNotEmpty(orderDeliveryDetailDO.getDetailAddress())) {
            detailAddress.append(orderDeliveryDetailDO.getDetailAddress());
        }
        return detailAddress.toString();
    }

    /**
     * ????????????
     *
     * @param fullOrderData ????????????
     * @param productType   ????????????
     */
    private FullOrderData addNewSubOrder(FullOrderData fullOrderData, Integer productType) {

        // ????????????
        OrderInfoDO orderInfoDO = fullOrderData.getOrderInfoDO();
        // ???????????????
        List<OrderItemDO> orderItemDOList = fullOrderData.getOrderItemDOList();
        // ?????????????????????
        OrderDeliveryDetailDO orderDeliveryDetailDO = fullOrderData.getOrderDeliveryDetailDO();
        // ?????????????????????
        List<OrderPaymentDetailDO> orderPaymentDetailDOList = fullOrderData.getOrderPaymentDetailDOList();
        // ?????????????????????
        List<OrderAmountDO> orderAmountDOList = fullOrderData.getOrderAmountDOList();
        // ?????????????????????
        List<OrderAmountDetailDO> orderAmountDetailDOList = fullOrderData.getOrderAmountDetailDOList();
        // ?????????????????????????????????
        OrderOperateLogDO orderOperateLogDO = fullOrderData.getOrderOperateLogDO();
        // ?????????????????????
        List<OrderSnapshotDO> orderSnapshotDOList = fullOrderData.getOrderSnapshotDOList();


        // ????????????
        String orderId = orderInfoDO.getOrderId();
        // ??????ID
        String userId = orderInfoDO.getUserId();

        // ?????????????????????????????????
        String subOrderId = orderNoManager.genOrderId(OrderNoTypeEnum.SALE_ORDER.getCode(), userId);

        // ????????????????????????
        FullOrderData subFullOrderData = new FullOrderData();

        // ????????????????????????????????????????????????
        List<OrderItemDO> subOrderItemDOList = orderItemDOList.stream()
                .filter(orderItemDO -> productType.equals(orderItemDO.getProductType()))
                .collect(Collectors.toList());

        // ?????????????????????
        Integer subTotalAmount = 0;
        Integer subRealPayAmount = 0;
        for (OrderItemDO subOrderItemDO : subOrderItemDOList) {
            subTotalAmount += subOrderItemDO.getOriginAmount();
            subRealPayAmount += subOrderItemDO.getPayAmount();
        }

        // ???????????????
        OrderInfoDO newSubOrderInfo = orderConverter.copyOrderInfoDTO(orderInfoDO);
        newSubOrderInfo.setId(null);
        newSubOrderInfo.setOrderId(subOrderId);
        newSubOrderInfo.setParentOrderId(orderId);
        newSubOrderInfo.setOrderStatus(OrderStatusEnum.INVALID.getCode());
        newSubOrderInfo.setTotalAmount(subTotalAmount);
        newSubOrderInfo.setPayAmount(subRealPayAmount);
        subFullOrderData.setOrderInfoDO(newSubOrderInfo);

        // ????????????
        List<OrderItemDO> newSubOrderItemList = new ArrayList<>();
        for (OrderItemDO orderItemDO : subOrderItemDOList) {
            OrderItemDO newSubOrderItem = orderConverter.copyOrderItemDO(orderItemDO);
            newSubOrderItem.setId(null);
            newSubOrderItem.setOrderId(subOrderId);
            String subOrderItemId = getSubOrderItemId(orderItemDO.getOrderItemId(), subOrderId);
            newSubOrderItem.setOrderItemId(subOrderItemId);
            newSubOrderItemList.add(newSubOrderItem);
        }
        subFullOrderData.setOrderItemDOList(newSubOrderItemList);

        // ????????????????????????
        OrderDeliveryDetailDO newSubOrderDeliveryDetail = orderConverter.copyOrderDeliverDetailDO(orderDeliveryDetailDO);
        newSubOrderDeliveryDetail.setId(null);
        newSubOrderDeliveryDetail.setOrderId(subOrderId);
        subFullOrderData.setOrderDeliveryDetailDO(newSubOrderDeliveryDetail);


        Map<String, OrderItemDO> subOrderItemMap = subOrderItemDOList.stream()
                .collect(Collectors.toMap(OrderItemDO::getOrderItemId, Function.identity()));

        // ???????????????????????????
        Integer subTotalOriginPayAmount = 0;
        Integer subTotalCouponDiscountAmount = 0;
        Integer subTotalRealPayAmount = 0;

        // ??????????????????
        List<OrderAmountDetailDO> subOrderAmountDetailList = new ArrayList<>();
        for (OrderAmountDetailDO orderAmountDetailDO : orderAmountDetailDOList) {
            String orderItemId = orderAmountDetailDO.getOrderItemId();
            if (!subOrderItemMap.containsKey(orderItemId)) {
                continue;
            }
            OrderAmountDetailDO subOrderAmountDetail = orderConverter.copyOrderAmountDetail(orderAmountDetailDO);
            subOrderAmountDetail.setId(null);
            subOrderAmountDetail.setOrderId(subOrderId);
            String subOrderItemId = getSubOrderItemId(orderItemId, subOrderId);
            subOrderAmountDetail.setOrderItemId(subOrderItemId);
            subOrderAmountDetailList.add(subOrderAmountDetail);

            Integer amountType = orderAmountDetailDO.getAmountType();
            Integer amount = orderAmountDetailDO.getAmount();
            if (AmountTypeEnum.ORIGIN_PAY_AMOUNT.getCode().equals(amountType)) {
                subTotalOriginPayAmount += amount;
            }
            if (AmountTypeEnum.COUPON_DISCOUNT_AMOUNT.getCode().equals(amountType)) {
                subTotalCouponDiscountAmount += amount;
            }
            if (AmountTypeEnum.REAL_PAY_AMOUNT.getCode().equals(amountType)) {
                subTotalRealPayAmount += amount;
            }
        }
        subFullOrderData.setOrderAmountDetailDOList(subOrderAmountDetailList);

        // ??????????????????
        List<OrderAmountDO> subOrderAmountList = new ArrayList<>();
        for (OrderAmountDO orderAmountDO : orderAmountDOList) {
            Integer amountType = orderAmountDO.getAmountType();
            OrderAmountDO subOrderAmount = orderConverter.copyOrderAmountDO(orderAmountDO);
            subOrderAmount.setId(null);
            subOrderAmount.setOrderId(subOrderId);
            if (AmountTypeEnum.ORIGIN_PAY_AMOUNT.getCode().equals(amountType)) {
                subOrderAmount.setAmount(subTotalOriginPayAmount);
                subOrderAmountList.add(subOrderAmount);
            }
            if (AmountTypeEnum.COUPON_DISCOUNT_AMOUNT.getCode().equals(amountType)) {
                subOrderAmount.setAmount(subTotalCouponDiscountAmount);
                subOrderAmountList.add(subOrderAmount);
            }
            if (AmountTypeEnum.REAL_PAY_AMOUNT.getCode().equals(amountType)) {
                subOrderAmount.setAmount(subTotalRealPayAmount);
                subOrderAmountList.add(subOrderAmount);
            }
        }
        subFullOrderData.setOrderAmountDOList(subOrderAmountList);

        // ??????????????????
        List<OrderPaymentDetailDO> subOrderPaymentDetailDOList = new ArrayList<>();
        for (OrderPaymentDetailDO orderPaymentDetailDO : orderPaymentDetailDOList) {
            OrderPaymentDetailDO subOrderPaymentDetail = orderConverter.copyOrderPaymentDetailDO(orderPaymentDetailDO);
            subOrderPaymentDetail.setId(null);
            subOrderPaymentDetail.setOrderId(subOrderId);
            subOrderPaymentDetail.setPayAmount(subTotalRealPayAmount);
            subOrderPaymentDetailDOList.add(subOrderPaymentDetail);
        }
        subFullOrderData.setOrderPaymentDetailDOList(subOrderPaymentDetailDOList);

        // ??????????????????????????????
        OrderOperateLogDO subOrderOperateLogDO = orderConverter.copyOrderOperationLogDO(orderOperateLogDO);
        subOrderOperateLogDO.setId(null);
        subOrderOperateLogDO.setOrderId(subOrderId);
        subFullOrderData.setOrderOperateLogDO(subOrderOperateLogDO);

        // ????????????????????????
        List<OrderSnapshotDO> subOrderSnapshotDOList = new ArrayList<>();
        for (OrderSnapshotDO orderSnapshotDO : orderSnapshotDOList) {
            OrderSnapshotDO subOrderSnapshotDO = orderConverter.copyOrderSnapshot(orderSnapshotDO);
            subOrderSnapshotDO.setId(null);
            subOrderSnapshotDO.setOrderId(subOrderId);
            if (SnapshotTypeEnum.ORDER_AMOUNT.getCode().equals(orderSnapshotDO.getSnapshotType())) {
                subOrderSnapshotDO.setSnapshotJson(JsonUtil.object2Json(subOrderAmountList));
            } else if (SnapshotTypeEnum.ORDER_ITEM.getCode().equals(orderSnapshotDO.getSnapshotType())) {
                subOrderSnapshotDO.setSnapshotJson(JsonUtil.object2Json(subOrderItemDOList));
            }
            subOrderSnapshotDOList.add(subOrderSnapshotDO);
        }
        subFullOrderData.setOrderSnapshotDOList(subOrderSnapshotDOList);
        return subFullOrderData;
    }

    /**
     * ??????????????????orderItemId???
     */
    private String getSubOrderItemId(String orderItemId, String subOrderId) {
        String postfix = orderItemId.substring(orderItemId.indexOf("_"));
        return subOrderId + postfix;
    }


}