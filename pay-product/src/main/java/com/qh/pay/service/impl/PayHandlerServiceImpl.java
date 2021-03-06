package com.qh.pay.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.redisson.api.RLock;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.alibaba.fastjson.JSONObject;
import com.qh.common.config.CfgKeyConst;
import com.qh.pay.api.Order;
import com.qh.pay.api.constenum.AcctType;
import com.qh.pay.api.constenum.AgentLevel;
import com.qh.pay.api.constenum.BankCode;
import com.qh.pay.api.constenum.CardType;
import com.qh.pay.api.constenum.CertType;
import com.qh.pay.api.constenum.ClearState;
import com.qh.pay.api.constenum.CollType;
import com.qh.pay.api.constenum.Currency;
import com.qh.pay.api.constenum.FeeType;
import com.qh.pay.api.constenum.OrderParamKey;
import com.qh.pay.api.constenum.OrderState;
import com.qh.pay.api.constenum.OrderType;
import com.qh.pay.api.constenum.OutChannel;
import com.qh.pay.api.constenum.PaymentMethod;
import com.qh.pay.api.constenum.ProfitLoss;
import com.qh.pay.api.utils.DateUtil;
import com.qh.pay.api.utils.ParamUtil;
import com.qh.pay.dao.PayOrderDao;
import com.qh.pay.dao.RecordFoundAvailAcctDao;
import com.qh.pay.dao.RecordMerchAvailBalDao;
import com.qh.pay.dao.RecordPayMerchAvailBalDao;
import com.qh.pay.dao.RecordPayMerchBalDao;
import com.qh.pay.domain.Agent;
import com.qh.pay.domain.MerchCharge;
import com.qh.pay.domain.MerchUserSignDO;
import com.qh.pay.domain.Merchant;
import com.qh.pay.domain.PayAcctBal;
import com.qh.pay.domain.RecordFoundAcctDO;
import com.qh.pay.domain.RecordMerchBalDO;
import com.qh.pay.domain.RecordPayMerchBalDO;
import com.qh.pay.service.AgentService;
import com.qh.pay.service.MerchantService;
import com.qh.pay.service.PayHandlerService;
import com.qh.pay.service.PayService;
import com.qh.redis.RedisConstants;
import com.qh.redis.service.RedisUtil;
import com.qh.redis.service.RedissonLockUtil;

/**
 * 
 * @ClassName PayHandlerServiceImpl
 * @Description ???????????????
 * @Date 2017???11???20??? ??????7:20:30
 * @version 1.0.0
 */
@Service
public class PayHandlerServiceImpl implements PayHandlerService{
	
	private static final org.slf4j.Logger logger = LoggerFactory.getLogger(PayHandlerServiceImpl.class);
	
	@Autowired
	private PayOrderDao payOrderDao;
	
	@Autowired
	private MerchantService merchantService;
	
	@Autowired
	private AgentService agentService;
	
	@Autowired
	private RecordFoundAvailAcctDao recordFoundAvailAcctDao;
	
	@Autowired
	private RecordMerchAvailBalDao recordMerchAvailBalDao;
	
	@Autowired
	private RecordPayMerchAvailBalDao rdPayMerchAvailBalDao;
	
	
	/* (??? Javadoc)
	 * Description:
	 * @see com.qh.pay.service.PayHandlerService#checkUserWithDrawOrder(com.qh.pay.api.Order)
	 */
	@Override
	public String checkUserWithDrawOrder(Order order) {
		// ????????????
		order.setOrderState(OrderState.init.id());
		// ????????????
		order.setOutChannel(OutChannel.acp.name());
		if(ParamUtil.isNotEmpty(order.getBankNo()) && order.getBankNo().length() > 25){
			return "????????????bankNo?????????25???";
		}
		if (ParamUtil.isEmpty(order.getBankCode()) || !BankCode.desc().containsKey(order.getBankCode())) {
			return "????????????bankCode??????????????????";
		}
		if(ParamUtil.isNotEmpty(order.getBankName()) && order.getBankName().length() > 20){
			return "????????????bankName?????????20???";
		}
		
		if (ParamUtil.isEmpty(order.getCardType())) {
			order.setCardType(CardType.savings.id());
		}else if(!CardType.desc().containsKey(order.getCardType())){
			return "???????????????cardType????????????";
		}
		
		if (ParamUtil.isEmpty(order.getAcctType())) {
			order.setAcctType(AcctType.pri.id());
		}else if(!AcctType.desc().containsKey(order.getAcctType())){
			return "????????????acctType????????????";
		}
		
		if(ParamUtil.isEmpty(order.getAcctName())){
			return "?????????acctName????????????";
		}
		if(ParamUtil.isEmpty(order.getCertType())){
			order.setCertType(CertType.identity.id());
		}else if(!CertType.desc().containsKey(order.getAcctType())){
			return "????????????certType????????????";
		}
			
		if(ParamUtil.isEmpty(order.getCertNo())){
			return "????????????certNo??????";
		}else if(order.getCertNo().length() > 20){
			return "????????????certNo?????????20???";
		}
		// ??????
		if(ParamUtil.isNotEmpty(order.getTitle()) && order.getTitle().length() > 20){
			return "??????title?????????20???";
		}
		
		// ????????????
		if(ParamUtil.isNotEmpty(order.getProduct()) && order.getProduct().length() > 20){
			return "????????????product?????????20???";
		}
		// ??????
		if (ParamUtil.isEmpty(order.getAmount())) {
			return "????????????amount??????";
		} else if(!ParamUtil.ifMoney(order.getAmount().toPlainString())){
			return "????????????amount????????????";
		}
		if (order.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
			return "????????????amount????????????";
		}
		// ??????
		order.setCurrency(Currency.CNY.name());
		// ??????????????????
		if(ParamUtil.isNotEmpty(order.getReturnUrl()) && order.getReturnUrl().length() > 100){
			return "??????????????????returnUrl?????????100???";
		}
		// ??????????????????
		if(ParamUtil.isNotEmpty(order.getNotifyUrl()) && order.getNotifyUrl().length() > 100){
			return "??????????????????notifyUrl?????????100???";
		}
		// ????????????
		order.setReqTime(DateUtil.getCurrentNumStr());
		
		// ??????
		if(ParamUtil.isNotEmpty(order.getMemo()) && order.getMemo().length() > 50){
			return "??????momo?????????50???";
		}
		order.setCrtDate(DateUtil.getCurrentTimeInt());
		return null;
	}
	
	/**
	 * 
	 * @Description ?????????????????????????????????????????????
	 * @param resultMap
	 * @return
	 */
	@Override
	public String initQrOrder(Order order, JSONObject jo) {
		// ????????????
		order.setOrderState(OrderState.init.id());
		// ?????????
		order.setMerchNo(jo.getString(OrderParamKey.merchNo.name()));
		// ?????????
		order.setOrderNo(jo.getString(OrderParamKey.orderNo.name()));
		if (ParamUtil.isEmpty(order.getOrderNo())) {
			return "?????????orderNo??????";
		}else if(order.getOrderNo().length() > 20){
			return "?????????orderNo?????????20???";
		}
		// ??????
		order.setTitle(jo.getString(OrderParamKey.title.name()));
		if(ParamUtil.isNotEmpty(order.getTitle()) && order.getTitle().length() > 20){
			return "??????title?????????20???";
		}
		// ????????????
		order.setProduct(jo.getString(OrderParamKey.product.name()));
		if (ParamUtil.isEmpty(order.getProduct())) {
			return "????????????product??????????????????";
		}else if(order.getProduct().length() > 20){
			return "????????????product?????????20???";
		}
		// ??????
		String amount = jo.getString(OrderParamKey.amount.name());
		if (ParamUtil.isEmpty(amount)) {
			return "????????????amount??????";
		} else if(!ParamUtil.ifMoney(amount)){
			return "????????????amount????????????";
		}else{
			order.setAmount(new BigDecimal(ParamUtil.subZeroAndDot(amount)));
		}
		if (order.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
			return "????????????amount????????????";
		}
		// ??????
		order.setCurrency(Currency.CNY.name());
		// ??????????????????
		order.setReturnUrl(jo.getString(OrderParamKey.returnUrl.name()));
		if(ParamUtil.isNotEmpty(order.getReturnUrl()) && order.getReturnUrl().length() > 100){
			return "??????????????????returnUrl?????????100???";
		}
		// ??????????????????
		order.setNotifyUrl(jo.getString(OrderParamKey.notifyUrl.name()));
		if(ParamUtil.isNotEmpty(order.getNotifyUrl()) && order.getNotifyUrl().length() > 100){
			return "??????????????????notifyUrl?????????100???";
		}
		// ????????????
		order.setReqTime(jo.getString(OrderParamKey.reqTime.name()));
		if (ParamUtil.isEmpty(order.getReqTime())) {
			return "????????????reqTime??????";
		}else if(order.getReqTime().length() > 15){
			return "????????????reqTime??????";
		}else if(!order.getReqTime().matches("\\d*")){
			return "????????????reqTime???????????????";
		}
		// ??????ip
		order.setReqIp(jo.getString(OrderParamKey.reqIp.name()));
		// ????????? ??????????????????
		order.setUserId(jo.getString(OrderParamKey.userId.name()));
		if(ParamUtil.isEmpty(order.getUserId())){
			return "????????????userId??????";
		}else if(order.getUserId().length() > 20){
			return "????????????userId?????????20???";
		}
		// ??????
		order.setMemo(jo.getString(OrderParamKey.memo.name()));
		if(ParamUtil.isNotEmpty(order.getMemo()) && order.getMemo().length() > 50){
			return "??????momo?????????50???";
		}
		order.setCrtDate(DateUtil.getCurrentTimeInt());
		return null;
	}
	
	/**
	 * 
	 * @Description ???????????????\????????????
	 * @param resultMap
	 * @return
	 */
	@Override
	public String initOrder(Order order, JSONObject jo) {
		// ????????????
		order.setOrderState(OrderState.init.id());
		// ?????????
		order.setMerchNo(jo.getString(OrderParamKey.merchNo.name()));
		// ?????????
		order.setOrderNo(jo.getString(OrderParamKey.orderNo.name()));
		if (ParamUtil.isEmpty(order.getOrderNo())) {
			return "?????????orderNo??????";
		}else if(order.getOrderNo().length() > 32){
			return "?????????orderNo?????????32???";
		}
		// ????????????
		order.setOutChannel(jo.getString(OrderParamKey.outChannel.name()));
		if (ParamUtil.isEmpty(order.getOutChannel()) || !OutChannel.desc().containsKey(order.getOutChannel())) {
			return "????????????outChannel??????????????????";
		}
		// ????????????????????? ????????????
		if (OutChannel.wy.name().equals(order.getOutChannel()) || OutChannel.acp.name().equals(order.getOutChannel())) {
			order.setBankName(jo.getString(OrderParamKey.bankName.name()));
			if(ParamUtil.isNotEmpty(order.getBankName()) && order.getBankName().length() > 20){
				return "????????????bankName?????????20???";
			}
			order.setBankNo(jo.getString(OrderParamKey.bankNo.name()));
			if(ParamUtil.isNotEmpty(order.getBankNo()) && order.getBankNo().length() > 25){
				return "????????????bankNo?????????25???";
			}
			
			order.setBankCode(jo.getString(OrderParamKey.bankCode.name()));
			if (ParamUtil.isEmpty(order.getBankCode()) || !BankCode.desc().containsKey(order.getBankCode())) {
				return "????????????bankCode??????????????????";
			}
			String cardType = jo.getString(OrderParamKey.cardType.name());
			if (ParamUtil.isEmpty(cardType)) {
				order.setCardType(CardType.savings.id());
			}else if(!cardType.matches("\\d")){
				return "???????????????cardType?????????";
			}else{
				order.setCardType(Integer.parseInt(cardType));
				if(!CardType.desc().containsKey(order.getCardType())){
					return "???????????????cardType????????????";
				}
			}
			
			String acctType = jo.getString(OrderParamKey.acctType.name());
			if (ParamUtil.isEmpty(acctType)) {
				order.setAcctType(AcctType.pri.id());
			}else if(!acctType.matches("\\d")){
				return "????????????acctType?????????";
			}else{
				order.setAcctType(Integer.parseInt(acctType));
				if(!AcctType.desc().containsKey(order.getAcctType())){
					return "????????????acctType????????????";
				}
			}
		}
		
		if(OutChannel.acp.name().equals(order.getOutChannel())){
			order.setAcctName(jo.getString(OrderParamKey.acctName.name()));
			if(ParamUtil.isEmpty(order.getAcctName())){
				return "?????????acctName????????????";
			}
			String certType = jo.getString(OrderParamKey.certType.name());
			if(ParamUtil.isEmpty(certType)){
				order.setCertType(CertType.identity.id());
			}else if(!certType.matches("\\d")){
				return "????????????certType???????????????";
			}else {
				order.setCertType(Integer.parseInt(certType));
				return "????????????certType????????????";
			}
			order.setCertNo(jo.getString(OrderParamKey.certNo.name()));
			if(ParamUtil.isEmpty(order.getCertNo())){
				return "????????????certNo??????";
			}else if(order.getCertNo().length() > 20){
				return "????????????certNo?????????20???";
			}
			order.setMobile(jo.getString(OrderParamKey.mobile.name()));
			order.setBankBranch(jo.getString(OrderParamKey.bankBranch.name()));
			order.setBankCity(jo.getString(OrderParamKey.bankCity.name()));
			order.setBankProvince(jo.getString(OrderParamKey.bankProvince.name()));
			
			/*if(ParamUtil.isEmpty(order.getMobile())){
				return "??????????????????????????????";
			}else if(order.getCertNo().length() > 11){
				return "???????????????????????????????????????11???";
			}*/
		}
		// ??????
		order.setTitle(jo.getString(OrderParamKey.title.name()));
		if(ParamUtil.isNotEmpty(order.getTitle()) && order.getTitle().length() > 20){
			return "??????title?????????20???";
		}
		
		// ????????????
		order.setProduct(jo.getString(OrderParamKey.product.name()));
		if (ParamUtil.isEmpty(order.getProduct())) {
			return "????????????product??????????????????";
		}else if(order.getProduct().length() > 20){
			return "????????????product?????????20???";
		}
		// ??????
		String amount = jo.getString(OrderParamKey.amount.name());
		if (ParamUtil.isEmpty(amount)) {
			return "????????????amount??????";
		} else if(!ParamUtil.ifMoney(amount)){
			return "????????????amount????????????";
		}else{
			order.setAmount(new BigDecimal(ParamUtil.subZeroAndDot(amount)));
		}
		if (order.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
			return "????????????amount????????????";
		}
		// ??????
		order.setCurrency(Currency.CNY.name());
		// ??????????????????
		order.setReturnUrl(jo.getString(OrderParamKey.returnUrl.name()));
		if(ParamUtil.isNotEmpty(order.getReturnUrl()) && order.getReturnUrl().length() > 100){
			return "??????????????????returnUrl?????????100???";
		}
		// ??????????????????
		order.setNotifyUrl(jo.getString(OrderParamKey.notifyUrl.name()));
		if(ParamUtil.isNotEmpty(order.getNotifyUrl()) && order.getNotifyUrl().length() > 100){
			return "??????????????????notifyUrl?????????100???";
		}
		// ????????????
		order.setReqTime(jo.getString(OrderParamKey.reqTime.name()));
		if (ParamUtil.isEmpty(order.getReqTime())) {
			return "????????????reqTime??????";
		}else if(order.getReqTime().length() > 15){
			return "????????????reqTime??????";
		}else if(!order.getReqTime().matches("\\d*")){
			return "????????????reqTime???????????????";
		}
		// ??????ip
		order.setReqIp(jo.getString(OrderParamKey.reqIp.name()));
		// ????????? ??????????????????
		order.setUserId(jo.getString(OrderParamKey.userId.name()));
		if(ParamUtil.isEmpty(order.getUserId())){
			return "????????????userId??????";
		}else if(order.getUserId().length() > 20){
			return "????????????userId?????????20???";
		}
		// ??????
		order.setMemo(jo.getString(OrderParamKey.memo.name()));
		if(ParamUtil.isNotEmpty(order.getMemo()) && order.getMemo().length() > 50){
			return "??????momo?????????50???";
		}
		order.setCrtDate(DateUtil.getCurrentTimeInt());
		return null;
	}
	
	/* (??? Javadoc)
	 * Description:
	 * @see com.qh.pay.service.PayHandlerService#checkUserSign(com.qh.pay.domain.MerchUserSignDO)
	 */
	@Override
	public String checkUserSign(MerchUserSignDO userSign) {
		if(ParamUtil.isEmpty(userSign.getUserId()) || userSign.getUserId().length() > 20){
			return "????????????userId?????????????????????20????????????";
		}
		if(ParamUtil.isEmpty(userSign.getAcctName()) || userSign.getAcctName().length() > 10){
			return "?????????acctName?????????????????????????????????10";
		}
		if(ParamUtil.isEmpty(userSign.getAcctType())){
			userSign.setAcctType(AcctType.pri.id());
		}else if(!AcctType.desc().containsKey(userSign.getAcctType())){
			return "????????????acctType????????????";
		}
		if(ParamUtil.isEmpty(userSign.getBankNo()) || userSign.getBankNo().length() > 25){
			return "????????????bankNo?????????????????????25????????????";
		}
		if(ParamUtil.isEmpty(userSign.getCertType())){
			userSign.setCertType(CertType.identity.id());
		}else if(!CertType.desc().containsKey(userSign.getCertType())){
			return "????????????certType????????????";
		}
		if(ParamUtil.isEmpty(userSign.getPhone()) || userSign.getPhone().length() > 11){
			return "????????????phone?????????????????????11????????????";
		}
		if(ParamUtil.isEmpty(userSign.getCardType())){
			userSign.setCardType(CardType.savings.id());
		}else if(!CardType.desc().containsKey(userSign.getCardType())){
			return "???????????????cardType????????????";
		}
		if(CardType.credit.id() == userSign.getCardType()){
			if(ParamUtil.isEmpty(userSign.getCvv2()) || userSign.getCvv2().length()> 3){
				return "?????????cvv2?????????????????????????????????3???";
			}
			if(ParamUtil.isEmpty(userSign.getValidDate()) || userSign.getValidDate().length() > 4){
				return "?????????validDate?????????????????????????????????4???";
			}
		}
		if(ParamUtil.isEmpty(userSign.getCollType())){
			userSign.setCollType(CollType.qpay.id());
		}else if(!CollType.desc().containsKey(userSign.getCollType())){
			return "????????????collType????????????";
		}
		return null;
	}

	/**
	 * ???????????? ?????????????????? ??????????????? ??????
	 */
	@Override
	public void companyMerchCapitalPoolRecord(Order order){
		String key = RedisConstants.CACHE_COMPANY_MERCHANT_CAPITAL_POOL + order.getPayCompany() + order.getPayMerch();
		RLock merchLock = RedissonLockUtil.getLock(key);
		try {
			merchLock.lock();
			JSONObject useCapitalPoolJo = (JSONObject) RedisUtil.getRedisTemplate().opsForHash().get(
					RedisConstants.CACHE_COMPANY_MERCHANT_CAPITAL_POOL + order.getPayCompany(), order.getPayMerch());
			if (useCapitalPoolJo != null) {
				BigDecimal curMoney = useCapitalPoolJo.getBigDecimal(RedisConstants.COMPANY_MERCHANT_CUR_MONEY);
				curMoney = curMoney == null ? new BigDecimal(0) : curMoney;
				curMoney = curMoney.add(order.getAmount());
				useCapitalPoolJo.put(RedisConstants.COMPANY_MERCHANT_CUR_MONEY, curMoney);
				RedisUtil.getRedisTemplate().opsForHash().put(
						RedisConstants.CACHE_COMPANY_MERCHANT_CAPITAL_POOL + order.getPayCompany(), order.getPayMerch(),
						useCapitalPoolJo);
			} 
		} finally {
			merchLock.unlock();
		}
	}
	
	/**
	 * ????????????/?????? ????????????
	 */
	@Override
	public void merchLimitRecord(Order order) {
		
		String key = CfgKeyConst.MERCHANT_DAY_LIMIT+order.getMerchNo();
		RLock merchLock = RedissonLockUtil.getLock(key);
		try {
			merchLock.lock();
			Object value = RedisUtil.getValue(key);
			if (value != null) {
				BigDecimal amountDay = (BigDecimal) value;
				amountDay = amountDay.add(order.getAmount());
				RedisUtil.setValue(key, amountDay);
				RedisUtil.getRedisTemplate().expire(key, DateUtil.getDayLeftSeconds(), TimeUnit.SECONDS);
			}
			key = CfgKeyConst.MERCHANT_MONTH_LIMIT + order.getMerchNo();
			value = null;
			value = RedisUtil.getValue(key);
			if (value != null) {
				BigDecimal amountMonth = (BigDecimal) value;
				amountMonth = amountMonth.add(order.getAmount());
				RedisUtil.setValue(key, amountMonth);
				RedisUtil.getRedisTemplate().expire(key, DateUtil.getMonthLeftSeconds(), TimeUnit.SECONDS);
			} 
		} finally {
			merchLock.unlock();
		}
	}
	
	/**
	 * 
	 * @Description ??????????????????  ??????
	 * @param order
	 * @param amount
	 * @param feeType
	 * @param orderType
	 * @return
	 */
	@Override
	public RecordMerchBalDO balForMerchSub(Order order, BigDecimal amount, int feeType, int orderType) {
		RecordMerchBalDO rdMerchBal = PayService.initRdMerchBal(order, feeType, orderType, ProfitLoss.loss.id());
		rdMerchBal.setCrtDate(DateUtil.getCurrentTimeInt());
		rdMerchBal.setTranAmt(amount);
		RLock merchLock = RedissonLockUtil.getBalMerchLock(order.getMerchNo());
		try {
			merchLock.lock();
			PayAcctBal pab = RedisUtil.getMerchBal(order.getMerchNo());
			rdMerchBal.setBeforeAmt(pab.getBalance());
			// ????????????
			pab.setBalance(pab.getBalance().subtract(amount).setScale(2, RoundingMode.HALF_UP));
			rdMerchBal.setAfterAmt(pab.getBalance());
			RedisUtil.setMerchBal(pab);
		} finally {
			merchLock.unlock();
		}
		return rdMerchBal;
	}
	/**
	 * 
	 * @Description ?????????????????? ??????
	 * @param order
	 * @param amount
	 * @param feeType
	 * @param orderType
	 * @return
	 */
	@Override
	public RecordMerchBalDO balForMerchAdd(Order order, BigDecimal amount, int feeType, int orderType) {
		RecordMerchBalDO rdMerchBal = PayService.initRdMerchBal(order, feeType, orderType, ProfitLoss.profit.id());
		rdMerchBal.setCrtDate(DateUtil.getCurrentTimeInt());
		rdMerchBal.setTranAmt(amount);
		RLock merchLock = RedissonLockUtil.getBalMerchLock(order.getMerchNo());
		try {
			merchLock.lock();
			PayAcctBal pab = RedisUtil.getMerchBal(order.getMerchNo());
			rdMerchBal.setBeforeAmt(pab.getBalance());
			pab.setBalance(pab.getBalance().add(rdMerchBal.getTranAmt()).setScale(2, RoundingMode.HALF_UP));
			rdMerchBal.setAfterAmt(pab.getBalance());
			RedisUtil.setMerchBal(pab);
		} finally {
			merchLock.unlock();
		}
		return rdMerchBal;
	}

	
	/**
	 * 
	 * @Description ???????????????????????? ??????
	 * @param order
	 * @param amount
	 * @param agentUser
	 * @param feeType
	 * @param orderType
	 * @return
	 */
	@Override
	public RecordFoundAcctDO balForAgentSub(Order order,BigDecimal amount, String agentUser, int feeType, int orderType) {
		RecordFoundAcctDO rdFoundAcct = PayService.initRdFoundAcct(order, feeType, orderType, ProfitLoss.loss.id());
		rdFoundAcct.setCrtDate(DateUtil.getCurrentTimeInt());
		rdFoundAcct.setTranAmt(amount);
		rdFoundAcct.setUsername(agentUser);
		RLock lock = RedissonLockUtil.getBalFoundAcctLock(agentUser);
		try {
			lock.lock();
			PayAcctBal payAcctBal = RedisUtil.getAgentBal(agentUser);
			rdFoundAcct.setBeforeAmt(payAcctBal.getBalance());
			payAcctBal.setBalance(payAcctBal.getBalance().subtract(rdFoundAcct.getTranAmt()).setScale(2, RoundingMode.HALF_UP));
			rdFoundAcct.setAfterAmt(payAcctBal.getBalance());
			RedisUtil.setAgentBal(payAcctBal);
		} finally {
			lock.unlock();
		}
		return rdFoundAcct;
	}
	
	
	/**
	 * 
	 * @Description ???????????????????????? ??????
	 * @param order
	 * @param amount
	 * @param agentUser
	 * @param feeType
	 * @param orderType
	 * @return
	 */
	@Override
	public RecordFoundAcctDO balForAgentAdd(Order order, BigDecimal amount,String agentUser,int feeType, int orderType) {
		RecordFoundAcctDO rdFoundAcct = PayService.initRdFoundAcct(order, feeType, orderType, ProfitLoss.profit.id());
		rdFoundAcct.setCrtDate(DateUtil.getCurrentTimeInt());
		rdFoundAcct.setTranAmt(amount);
		rdFoundAcct.setUsername(agentUser);
		RLock lock = RedissonLockUtil.getBalFoundAcctLock(agentUser);
		try {
			lock.lock();
			PayAcctBal payAcctBal = RedisUtil.getAgentBal(agentUser);
			rdFoundAcct.setBeforeAmt(payAcctBal.getBalance());
			payAcctBal.setBalance(payAcctBal.getBalance().add(rdFoundAcct.getTranAmt()).setScale(2, RoundingMode.HALF_UP));
			rdFoundAcct.setAfterAmt(payAcctBal.getBalance());
			RedisUtil.setAgentBal(payAcctBal);
		} finally {
			lock.unlock();
		}
		return rdFoundAcct;
	}
	
	/**
	 * 
	 * @Description ???????????????????????? ??????
	 * @param order
	 * @param amount
	 * @param feeType
	 * @param orderType
	 * @return
	 */
	@Override
	public RecordFoundAcctDO balForPlatSub(Order order, BigDecimal amount, int feeType,int orderType) {
		RecordFoundAcctDO rdFoundAcct = PayService.initRdFoundAcct(order, feeType, orderType, ProfitLoss.loss.id());
		rdFoundAcct.setCrtDate(DateUtil.getCurrentTimeInt());
		rdFoundAcct.setTranAmt(amount);
		RLock lock = RedissonLockUtil.getBalFoundAcctLock();
		try {
			lock.lock();
			PayAcctBal payAcctBal = RedisUtil.getPayFoundBal();
			rdFoundAcct.setUsername(payAcctBal.getUsername());
			rdFoundAcct.setBeforeAmt(payAcctBal.getBalance());
			payAcctBal.setBalance(payAcctBal.getBalance().subtract(rdFoundAcct.getTranAmt()).setScale(2, RoundingMode.HALF_UP));
			rdFoundAcct.setAfterAmt(payAcctBal.getBalance());
			RedisUtil.setPayFoundBal(payAcctBal);
		} finally {
			lock.unlock();
		}
		return rdFoundAcct;
	}
	
	/**
	 * @Description ???????????????????????? ??????
	 * @param order
	 * @return
	 */
	@Override
	public RecordFoundAcctDO balForPlatAdd(Order order, BigDecimal amount, int feeType, int orderType) {
		RecordFoundAcctDO rdFoundAcct = PayService.initRdFoundAcct(order, feeType, orderType, ProfitLoss.profit.id());
		rdFoundAcct.setCrtDate(DateUtil.getCurrentTimeInt());
		rdFoundAcct.setTranAmt(amount);
		RLock lock = RedissonLockUtil.getBalFoundAcctLock();
		try {
			lock.lock();
			PayAcctBal payAcctBal = RedisUtil.getPayFoundBal();
			rdFoundAcct.setUsername(payAcctBal.getUsername());
			rdFoundAcct.setBeforeAmt(payAcctBal.getBalance());
			payAcctBal.setBalance(payAcctBal.getBalance().add(rdFoundAcct.getTranAmt()).setScale(2, RoundingMode.HALF_UP));
			rdFoundAcct.setAfterAmt(payAcctBal.getBalance());
			RedisUtil.setPayFoundBal(payAcctBal);
		} finally {
			lock.unlock();
		}
		return rdFoundAcct;
	}
	
	/**
	 * 
	 * @Description ??????????????????????????????????????? ??????
	 * @param order
	 * @param amount
	 * @param feeType
	 * @param orderType
	 * @return
	 */
	@Override
	public RecordPayMerchBalDO balForPayMerchSub(Order order, BigDecimal amount, int feeType,int orderType) {
		RecordPayMerchBalDO rdPayMerchAcct = PayService.initRdPayMerchAcct(order, feeType, orderType, ProfitLoss.loss.id());
		rdPayMerchAcct.setCrtDate(DateUtil.getCurrentTimeInt());
		rdPayMerchAcct.setTranAmt(amount);
		RLock lock = RedissonLockUtil.getBalPayMerchLock(order.getPayCompany()+"_"+ order.getPayMerch());
		try {
			lock.lock();
			PayAcctBal payAcctBal = RedisUtil.getPayMerchBal(order.getPayCompany(), order.getPayMerch());
			rdPayMerchAcct.setBeforeAmt(payAcctBal.getBalance());
			payAcctBal.setBalance(payAcctBal.getBalance().subtract(rdPayMerchAcct.getTranAmt()).setScale(2, RoundingMode.HALF_UP));
			rdPayMerchAcct.setAfterAmt(payAcctBal.getBalance());
			RedisUtil.setPayMerchBal(payAcctBal, order.getPayCompany(), order.getPayMerch());
		} finally {
			lock.unlock();
		}
		return rdPayMerchAcct;
	}
	
	/**
	 * @Description ??????????????????????????????????????? ??????
	 * @param order
	 * @return
	 */
	@Override
	public RecordPayMerchBalDO balForPayMerchAdd(Order order, BigDecimal amount, int feeType, int orderType) {
		RecordPayMerchBalDO rdPayMerchAcct = PayService.initRdPayMerchAcct(order, feeType, orderType, ProfitLoss.profit.id());
		rdPayMerchAcct.setCrtDate(DateUtil.getCurrentTimeInt());
		rdPayMerchAcct.setTranAmt(amount);
		RLock lock = RedissonLockUtil.getBalPayMerchLock(order.getPayCompany()+"_"+ order.getPayMerch());
		try {
			lock.lock();
			PayAcctBal payAcctBal = RedisUtil.getPayMerchBal(order.getPayCompany(), order.getPayMerch());
			rdPayMerchAcct.setBeforeAmt(payAcctBal.getBalance());
			payAcctBal.setBalance(payAcctBal.getBalance().add(rdPayMerchAcct.getTranAmt()).setScale(2, RoundingMode.HALF_UP));
			rdPayMerchAcct.setAfterAmt(payAcctBal.getBalance());
			RedisUtil.setPayMerchBal(payAcctBal, order.getPayCompany(), order.getPayMerch());
		} finally {
			lock.unlock();
		}
		return rdPayMerchAcct;
	}

	/* (??? Javadoc)
	 * Description:
	 * @see com.qh.pay.service.PayHandlerService#orderClear(java.lang.String)
	 */
	@Override
	public void orderClear(String company) {
		int endDate = DateUtil.getBeginTimeIntZero() - 1;
		//???????????????  ???????????????????????????????????????
		int beginDate = endDate - 8*24 * 60 *60 + 1;
		
		this.ordeClear(company, beginDate,endDate);
	}

	/**
	 * @Description ?????????????????????
	 * @param company
	 * @param endDate
	 * @param beginDate
	 */
	private void ordeClear(String company, int beginDate , int endDate) {
		Map<String,Object> params = new HashMap<>();
		params.put("beginDate", beginDate);
		params.put("endDate", endDate);
		if(PaymentMethod.descName().containsKey(company)) {
			params.put("paymentMethod", PaymentMethod.idMap().get(company));
		}else {
			Pattern pattern = Pattern.compile("[a-zA-Z]{1,4}(DL|SH)[0-9]{4,6}");
			Matcher matcher = pattern.matcher(company); 
			if(matcher.matches()) {
				params.put("merchNo", company);
			}else
				params.put("payCompany", company);
		}
		params.put("orderState", OrderState.succ.id());
		params.put("clearState", ClearState.init.id());
		List<Order> orders = payOrderDao.list(params);
		int i = 0;
		List<Order> updateOrders = new ArrayList<Order>();
		int succ = 0;
		for (Order order : orders) {
			i++;
			Integer clearState = order.getClearState();
			if(clearState == ClearState.ing.id()) {
				BigDecimal clearAmountThe = ParamUtil.sub(order.getAmount(), order.getClearAmount());
				order.setClearAmountThe(clearAmountThe);
			}
			order.setClearAmount(order.getAmount());
			order.setClearState(ClearState.succ.id());
			updateOrders.add(order);
			if(i % 100 == 0){
				if(updateClearStateBatch(company, updateOrders) > 0){
					availBalForOrderClearSucc(updateOrders);
					succ += 100;
				}
				updateOrders.clear();
			}
		}
		if(updateOrders.size() > 0){
			if(updateClearStateBatch(company, updateOrders) > 0){
				availBalForOrderClearSucc(updateOrders);
				succ += updateOrders.size();
			}
			updateOrders.clear();
		}
		logger.info("?????????????????????????????????????????????{}??????????????????{}", i,succ);
	}
	
	@Override
	public void orderClear(String company, Date date) {
		if(ParamUtil.isEmpty(date)){
			this.orderClear(company);
		}else{
			int beginDate = DateUtil.getBeginTimeIntZero(date);
			
			/*if(beginDate >= DateUtil.getBeginTimeIntZero() && !PaymentMethod.D0.name().equals(company) && !PaymentMethod.T0.name().equals(company)){
				logger.warn("?????????????????????????????????{}", date);
				return;
			}*/
			int endDate =  beginDate + 24 * 60 *60 - 1;
			this.ordeClear(company,beginDate,endDate );
		}
	}
	/**
	 * @Description ??????????????????????????????
	 * @param updateOrders
	 */
	public void availBalForOrderClearSucc(List<Order> updateOrders) {
		List<RecordMerchBalDO> merchAvailBals = new ArrayList<>();
		List<RecordFoundAcctDO> foundAvailBals = new ArrayList<>();
		List<RecordPayMerchBalDO> payMerchBals = new ArrayList<>();
		for (Order order : updateOrders) {
			
			if(order.getClearAmountThe() != null && order.getClearAmountThe().compareTo(BigDecimal.ZERO) == 1) {
				//?????????????????? ??????
				//?????????????????????????????????????????????????????????
				BigDecimal clearAmount = order.getClearAmountThe();
				merchAvailBals.add(this.availBalForMerchAdd(order, clearAmount,
						FeeType.merchIn.id(),  OrderType.pay.id()));
				
				payMerchBals.add(this.availBalForPayMerchAdd(order,clearAmount, 
						FeeType.payMerchTrade.id(),OrderType.pay.id()));
			}else {
				
				//?????????????????? ??????
				merchAvailBals.add(this.availBalForMerchAdd(order, order.getClearAmount(),
						FeeType.merchIn.id(),  OrderType.pay.id()));
				
				//?????????????????? ??????  ?????????
				merchAvailBals.add(this.availBalForMerchSub(order, order.getQhAmount(),
						FeeType.merchHandFee.id(),  OrderType.pay.id()));
				
				// ????????????
				Merchant merchant = merchantService.get(order.getMerchNo());
				
				// ????????????
				Agent agent = agentService.get(merchant.getParentAgent());
				
	//			BigDecimal ptAmount = new BigDecimal(0);
				//??????????????????????????????
				if (agent.getLevel() == AgentLevel.two.id()) {
					
					foundAvailBals.add(this.availBalForAgentAdd(order, order.getSubAgentAmount(),merchant.getParentAgent(), FeeType.agentIn.id(),OrderType.pay.id()));
						
					foundAvailBals.add(this.availBalForAgentAdd(order,order.getAgentAmount(), agent.getParentAgent(), FeeType.agentIn.id(),OrderType.pay.id()));
				}else {
					foundAvailBals.add(this.availBalForAgentAdd(order, order.getAgentAmount(),merchant.getParentAgent(), FeeType.agentIn.id(),OrderType.pay.id()));
				}
				
	//			ptAmount = order.getAgentAmount();
				BigDecimal subAgentAmount = order.getSubAgentAmount();
				order.setSubAgentAmount(subAgentAmount==null?BigDecimal.ZERO:subAgentAmount);
				
				BigDecimal platMoney = order.getQhAmount().subtract(order.getCostAmount().add(order.getAgentAmount()).add(order.getSubAgentAmount()));
				platMoney = platMoney.compareTo(BigDecimal.ZERO) == -1 ? BigDecimal.ZERO : platMoney;
				//????????????????????????????????????
				foundAvailBals.add(this.availBalForPlatAdd(order,platMoney, 
						FeeType.platIn.id(),OrderType.pay.id()));
				
				// ???????????????????????????????????????????????????????????????
				payMerchBals.add(this.availBalForPayMerchAdd(order,order.getClearAmount(), 
						FeeType.payMerchTrade.id(),OrderType.pay.id()));
				
				
				// ???????????????????????????????????????????????????????????????    ????????????
				payMerchBals.add(this.availBalForPayMerchSub(order,order.getCostAmount(), 
						FeeType.payMerchTradeHand.id(),OrderType.pay.id()));
			}
		}
		//???????????????
		try {
			if(foundAvailBals.size() > 0)
				recordFoundAvailAcctDao.saveBatch(foundAvailBals);
			if(merchAvailBals.size() > 0)
				recordMerchAvailBalDao.saveBatch(merchAvailBals);
			if(payMerchBals.size() > 0)
				rdPayMerchAvailBalDao.saveBatch(payMerchBals);
		} catch (Exception e) {
			e.printStackTrace();
			logger.error("?????????????????????????????????????????????????????????");
		}
		
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public int updateClearStateBatch(String company,List<Order> updateOrders){
		try {
			return payOrderDao.updateClearStateBatch(updateOrders);
		} catch (Exception e) {
			logger.error("???????????????????????????????????????" + company , e);
		}
		return 0;
	}

	
	/**
	 * 
	 * @Description ???????????????????????????????????? ??????
	 * @param order
	 * @param amount
	 * @param agentUser
	 * @param feeType
	 * @param orderType
	 * @return
	 */
	@Override
	public RecordFoundAcctDO availBalForAgentAdd(Order order, BigDecimal amount,String agentUser,int feeType, int orderType) {
		RecordFoundAcctDO rdFoundAcct = PayService.initRdFoundAcct(order, feeType, orderType, ProfitLoss.profit.id());
		rdFoundAcct.setCrtDate(DateUtil.getCurrentTimeInt());
		rdFoundAcct.setTranAmt(amount);
		rdFoundAcct.setUsername(agentUser);
		RLock lock = RedissonLockUtil.getBalFoundAcctLock(agentUser);
		try {
			lock.lock();
			PayAcctBal pab = RedisUtil.getAgentBal(agentUser);
			this.payAcctAvailBalAdd(order, rdFoundAcct, pab);
			RedisUtil.setAgentBal(pab);
		} finally {
			lock.unlock();
		}
		return rdFoundAcct;
	}
	
	/* (??? Javadoc)
	 * Description:
	 * @see com.qh.pay.service.PayHandlerService#availBalForMerchSub(com.qh.pay.api.Order, java.math.BigDecimal, int, int)
	 */
	@Override
	public RecordMerchBalDO availBalForMerchSubForQr(Order order, BigDecimal amount, int feeType, int orderType) {
		RecordMerchBalDO rdMerchAvailBal = PayService.initRdMerchBal(order, feeType,orderType, ProfitLoss.loss.id());
		rdMerchAvailBal.setCrtDate(DateUtil.getCurrentTimeInt());
		rdMerchAvailBal.setTranAmt(amount);
		RLock lock = RedissonLockUtil.getBalMerchLock(order.getMerchNo());
		try {
			lock.lock();
			PayAcctBal pab = RedisUtil.getMerchBal(order.getMerchNo());
			this.payAcctAvailBalSubForQr(order,rdMerchAvailBal,pab);
			RedisUtil.setMerchBal(pab);
		} finally {
			lock.unlock();
		}
		
		return rdMerchAvailBal;
	}
	
	/**
	 * 
	 * @Description ???????????????????????? ??????
	 * @param order
	 * @param amount
	 * @param feeType
	 * @param orderType
	 * @return
	 */
	@Override
	public RecordMerchBalDO availBalForMerchAdd(Order order, BigDecimal amount, int feeType, int orderType) {
		RecordMerchBalDO rdMerchBal = PayService.initRdMerchBal(order, feeType, orderType, ProfitLoss.profit.id());
		rdMerchBal.setCrtDate(DateUtil.getCurrentTimeInt());
		rdMerchBal.setTranAmt(amount);
		RLock merchLock = RedissonLockUtil.getBalMerchLock(order.getMerchNo());
		try {
			merchLock.lock();
			PayAcctBal pab = RedisUtil.getMerchBal(order.getMerchNo());
			this.payAcctAvailBalAdd(order, rdMerchBal, pab);
			RedisUtil.setMerchBal(pab);
		} finally {
			merchLock.unlock();
		}
		return rdMerchBal;
	}

	/**
	 * @Description ?????????????????????????????? ??????
	 * @param order
	 * @return
	 */
	@Override
	public RecordFoundAcctDO availBalForPlatAdd(Order order, BigDecimal amount, int feeType, int orderType) {
		RecordFoundAcctDO rdFoundAcct = PayService.initRdFoundAcct(order, feeType, orderType, ProfitLoss.profit.id());
		rdFoundAcct.setCrtDate(DateUtil.getCurrentTimeInt());
		rdFoundAcct.setTranAmt(amount);
		RLock lock = RedissonLockUtil.getBalFoundAcctLock();
		try {
			lock.lock();
			PayAcctBal pab = RedisUtil.getPayFoundBal();
			this.payAcctAvailBalAdd(order, rdFoundAcct, pab);
			RedisUtil.setPayFoundBal(pab);
		} finally {
			lock.unlock();
		}
		return rdFoundAcct;
	}
	
	/**
	 * 
	 * @Description ??????????????????????????????????????? ??????
	 * @param order
	 * @param amount
	 * @param feeType
	 * @param orderType
	 * @return
	 */
	@Override
	public RecordPayMerchBalDO availBalForPayMerchAdd(Order order, BigDecimal amount, int feeType, int orderType) {
		
		RecordPayMerchBalDO rdPayMerchAcct = PayService.initRdPayMerchAcct(order, feeType, orderType, ProfitLoss.profit.id());
		rdPayMerchAcct.setCrtDate(DateUtil.getCurrentTimeInt());
		rdPayMerchAcct.setTranAmt(amount);
		RLock merchLock = RedissonLockUtil.getBalPayMerchLock(order.getPayCompany()+"_"+ order.getPayMerch());
		try {
			merchLock.lock();
			PayAcctBal pab = RedisUtil.getPayMerchBal(order.getPayCompany(), order.getPayMerch());
			this.payAcctAvailBalAdd(order, rdPayMerchAcct, pab);
			RedisUtil.setPayMerchBal(pab, order.getPayCompany(), order.getPayMerch());
		} finally {
			merchLock.unlock();
		}
		return rdPayMerchAcct;
	}

	/**
	 * @Description ????????????????????????
	 * @param order
	 * @param rdFoundAcct
	 * @param pab
	 */
	private void payAcctAvailBalAdd(Order order, RecordFoundAcctDO rdFoundAcct, PayAcctBal pab) {
		rdFoundAcct.setUsername(pab.getUsername());
		rdFoundAcct.setBeforeAmt(pab.getAvailBal());
		pab.setAvailBal(pab.getAvailBal().add(rdFoundAcct.getTranAmt()).setScale(2, RoundingMode.HALF_UP));
		rdFoundAcct.setAfterAmt(pab.getAvailBal());
		Map<String, BigDecimal> companyPayAvailBal = pab.getCompanyPayAvailBal();
		if (companyPayAvailBal == null) {
			companyPayAvailBal = new HashMap<>();
			pab.setCompanyPayAvailBal(companyPayAvailBal);
		}
		BigDecimal singleAvailBal = companyPayAvailBal.get(order.getPayCompany() + RedisConstants.link_symbol + order.getPayMerch());
		if (singleAvailBal == null) {
			singleAvailBal = BigDecimal.ZERO;
		}
		companyPayAvailBal.put(order.getPayCompany() + RedisConstants.link_symbol + order.getPayMerch(),
				singleAvailBal.add(rdFoundAcct.getTranAmt()).setScale(2, RoundingMode.HALF_UP));
	}

	/**
	 * @Description ????????????????????????
	 * @param order
	 * @param rdMerchBal
	 * @param pab
	 */
	private void payAcctAvailBalAdd(Order order, RecordMerchBalDO rdMerchBal, PayAcctBal pab) {
		rdMerchBal.setBeforeAmt(pab.getAvailBal());
		pab.setAvailBal(pab.getAvailBal().add(rdMerchBal.getTranAmt()).setScale(2, RoundingMode.HALF_UP));
		rdMerchBal.setAfterAmt(pab.getAvailBal());
		Map<String, BigDecimal> companyPayAvailBal = pab.getCompanyPayAvailBal();
		if (companyPayAvailBal == null) {
			companyPayAvailBal = new HashMap<>();
			pab.setCompanyPayAvailBal(companyPayAvailBal);
		}
		BigDecimal singleAvailBal = companyPayAvailBal.get(order.getPayCompany() + RedisConstants.link_symbol +  order.getPayMerch());
		if (singleAvailBal == null) {
			singleAvailBal = BigDecimal.ZERO;
		}
		companyPayAvailBal.put(order.getPayCompany() + RedisConstants.link_symbol + order.getPayMerch(),
				singleAvailBal.add(rdMerchBal.getTranAmt()).setScale(2, RoundingMode.HALF_UP));
	}
	
	/**
	 * @Description ????????????????????????
	 * @param order
	 * @param rdMerchBal
	 * @param pab
	 */
	private void payAcctAvailBalAdd(Order order, RecordPayMerchBalDO rdMerchBal, PayAcctBal pab) {
		rdMerchBal.setBeforeAmt(pab.getAvailBal());
		pab.setAvailBal(pab.getAvailBal().add(rdMerchBal.getTranAmt()).setScale(2, RoundingMode.HALF_UP));
		rdMerchBal.setAfterAmt(pab.getAvailBal());
		Map<String, BigDecimal> companyPayAvailBal = pab.getCompanyPayAvailBal();
		if (companyPayAvailBal == null) {
			companyPayAvailBal = new HashMap<>();
			pab.setCompanyPayAvailBal(companyPayAvailBal);
		}
		BigDecimal singleAvailBal = companyPayAvailBal.get(order.getPayCompany() + RedisConstants.link_symbol +  order.getPayMerch() + RedisConstants.link_symbol + order.getOutChannel());
		if (singleAvailBal == null) {
			singleAvailBal = BigDecimal.ZERO;
		}
		companyPayAvailBal.put(order.getPayCompany() + RedisConstants.link_symbol + order.getPayMerch() + RedisConstants.link_symbol + order.getOutChannel(),
				singleAvailBal.add(rdMerchBal.getTranAmt()).setScale(2, RoundingMode.HALF_UP));
	}

	
	/**
	 * 
	 * @Description ???????????????????????????????????? ??????
	 * @param order
	 * @param amount
	 * @param agentUser
	 * @param feeType
	 * @param orderType
	 * @return
	 */
	@Override
	public RecordFoundAcctDO availBalForPlatSub(Order order, BigDecimal amount,String agentUser,int feeType, int orderType) {
		RecordFoundAcctDO rdFoundAcct = PayService.initRdFoundAcct(order, feeType, orderType, ProfitLoss.loss.id());
		rdFoundAcct.setCrtDate(DateUtil.getCurrentTimeInt());
		rdFoundAcct.setTranAmt(amount);
		rdFoundAcct.setUsername(agentUser);
		RLock lock = RedissonLockUtil.getBalFoundAcctLock();
		try {
			lock.lock();
			PayAcctBal pab = RedisUtil.getPayFoundBal();
			this.payAcctAvailBalSub(order, rdFoundAcct, pab);
			RedisUtil.setPayFoundBal(pab);
		} finally {
			lock.unlock();
		}
		return rdFoundAcct;
	}
	
	/**
	 * 
	 * @Description ?????????????????????????????? ??????
	 * @param order
	 * @param amount
	 * @param feeType
	 * @param orderType
	 * @return
	 */
	@Override
	public void availBalForPlatSub(Order order, RecordFoundAcctDO rdFoundBal, PayAcctBal pab) {
		this.payAcctAvailBalSub(order, rdFoundBal, pab);
	}
	
	/**
	 * 
	 * @Description ???????????????????????????????????? ??????
	 * @param order
	 * @param amount
	 * @param agentUser
	 * @param feeType
	 * @param orderType
	 * @return
	 */
	@Override
	public RecordFoundAcctDO availBalForAgentSub(Order order, BigDecimal amount,String agentUser,int feeType, int orderType) {
		RecordFoundAcctDO rdFoundAcct = PayService.initRdFoundAcct(order, feeType, orderType, ProfitLoss.loss.id());
		rdFoundAcct.setCrtDate(DateUtil.getCurrentTimeInt());
		rdFoundAcct.setTranAmt(amount);
		rdFoundAcct.setUsername(agentUser);
		RLock lock = RedissonLockUtil.getBalFoundAcctLock(agentUser);
		try {
			lock.lock();
			PayAcctBal pab = RedisUtil.getAgentBal(agentUser);
			this.payAcctAvailBalSub(order, rdFoundAcct, pab);
			RedisUtil.setAgentBal(pab);
		} finally {
			lock.unlock();
		}
		return rdFoundAcct;
	}
	
	/**
	 * 
	 * @Description ?????????????????????????????? ??????
	 * @param order
	 * @param amount
	 * @param feeType
	 * @param orderType
	 * @return
	 */
	@Override
	public void availBalForAgentSub(Order order, RecordFoundAcctDO rdFoundBal, PayAcctBal pab) {
		this.payAcctAvailBalSub(order, rdFoundBal, pab);
	}
	
	/**
	 * 
	 * @Description ???????????????????????? ??????
	 * @param order
	 * @param amount
	 * @param feeType
	 * @param orderType
	 * @return
	 */
	@Override
	public RecordMerchBalDO availBalForMerchSub(Order order, BigDecimal amount, int feeType, int orderType) {
		RecordMerchBalDO rdMerchBal = PayService.initRdMerchBal(order, feeType, orderType, ProfitLoss.loss.id());
		rdMerchBal.setCrtDate(DateUtil.getCurrentTimeInt());
		rdMerchBal.setTranAmt(amount);
		RLock merchLock = RedissonLockUtil.getBalMerchLock(order.getMerchNo());
		try {
			merchLock.lock();
			PayAcctBal pab = RedisUtil.getMerchBal(order.getMerchNo());
			this.payAcctAvailBalSub(order, rdMerchBal, pab);
			RedisUtil.setMerchBal(pab);
		} finally {
			merchLock.unlock();
		}
		return rdMerchBal;
	}
	/**
	 * 
	 * @Description ???????????????????????? ??????
	 * @param order
	 * @param amount
	 * @param feeType
	 * @param orderType
	 * @return
	 */
	@Override
	public void availBalForMerchSub(Order order, RecordMerchBalDO rdMerchBal, PayAcctBal pab) {
		this.payAcctAvailBalSub(order, rdMerchBal, pab);
	}
	
	/**
	 * 
	 * @Description ??????????????????????????????????????? ??????
	 * @param order
	 * @param amount
	 * @param feeType
	 * @param orderType
	 * @return
	 */
	@Override
	public RecordPayMerchBalDO availBalForPayMerchSub(Order order, BigDecimal amount, int feeType, int orderType) {
		
		RecordPayMerchBalDO rdPayMerchAcct = PayService.initRdPayMerchAcct(order, feeType, orderType, ProfitLoss.loss.id());
		rdPayMerchAcct.setCrtDate(DateUtil.getCurrentTimeInt());
		rdPayMerchAcct.setTranAmt(amount);
		RLock merchLock = RedissonLockUtil.getBalPayMerchLock(order.getPayCompany()+"_"+ order.getPayMerch());
		try {
			merchLock.lock();
			PayAcctBal pab = RedisUtil.getPayMerchBal(order.getPayCompany(), order.getPayMerch());
			this.payAcctAvailBalSub(order, rdPayMerchAcct, pab);
			RedisUtil.setPayMerchBal(pab, order.getPayCompany(), order.getPayMerch());
		} finally {
			merchLock.unlock();
		}
		return rdPayMerchAcct;
	}
	
	/**
	 * 
	 * @Description ??????????????????????????????????????? ??????
	 * @param order
	 * @param amount
	 * @param feeType
	 * @param orderType
	 * @return
	 */
	@Override
	public void availBalForPayMerchSub(Order order, RecordPayMerchBalDO rdPayMerchAcct, PayAcctBal pab) {
		this.payAcctAvailBalSub(order, rdPayMerchAcct, pab);
	}
	
	/**
	 * @Description ????????????????????????
	 * @param order
	 * @param rdFoundAcct
	 * @param pab
	 */
	private void payAcctAvailBalSub(Order order, RecordFoundAcctDO rdFoundAcct, PayAcctBal pab) {
		rdFoundAcct.setUsername(pab.getUsername());
		rdFoundAcct.setBeforeAmt(pab.getAvailBal());
		pab.setAvailBal(pab.getAvailBal().subtract(rdFoundAcct.getTranAmt()).setScale(2, RoundingMode.HALF_UP));
		rdFoundAcct.setAfterAmt(pab.getAvailBal());
		Map<String, BigDecimal> companyPayAvailBal = pab.getCompanyPayAvailBal();
		if (companyPayAvailBal == null) {
			companyPayAvailBal = new HashMap<>();
			pab.setCompanyPayAvailBal(companyPayAvailBal);
		}
		BigDecimal singleAvailBal = companyPayAvailBal.get(order.getPayCompany() + RedisConstants.link_symbol + order.getPayMerch());
		if (singleAvailBal == null) {
			singleAvailBal = BigDecimal.ZERO;
		}
		companyPayAvailBal.put(order.getPayCompany() + RedisConstants.link_symbol + order.getPayMerch(),
				singleAvailBal.subtract(rdFoundAcct.getTranAmt()).setScale(2, RoundingMode.HALF_UP));
	}
	
	/**
	 * @Description ????????????????????????
	 * @param order
	 * @param rdMerchBal
	 * @param pab
	 */
	private void payAcctAvailBalSub(Order order, RecordMerchBalDO rdMerchBal, PayAcctBal pab) {
		rdMerchBal.setBeforeAmt(pab.getAvailBal());
		pab.setAvailBal(pab.getAvailBal().subtract(rdMerchBal.getTranAmt()).setScale(2, RoundingMode.HALF_UP));
		rdMerchBal.setAfterAmt(pab.getAvailBal());
		Map<String, BigDecimal> companyPayAvailBal = pab.getCompanyPayAvailBal();
		if (companyPayAvailBal == null) {
			companyPayAvailBal = new HashMap<>();
			pab.setCompanyPayAvailBal(companyPayAvailBal);
		}
		BigDecimal singleAvailBal = companyPayAvailBal.get(order.getPayCompany() + RedisConstants.link_symbol +  order.getPayMerch());
		if (singleAvailBal == null) {
			singleAvailBal = BigDecimal.ZERO;
		}
		companyPayAvailBal.put(order.getPayCompany() + RedisConstants.link_symbol + order.getPayMerch(),
				singleAvailBal.subtract(rdMerchBal.getTranAmt()).setScale(2, RoundingMode.HALF_UP));
	}
	
	/**
	 * @Description ????????????????????????
	 * @param order
	 * @param rdMerchBal
	 * @param pab
	 */
	private void payAcctAvailBalSub(Order order, RecordPayMerchBalDO rdMerchBal, PayAcctBal pab) {
		rdMerchBal.setBeforeAmt(pab.getAvailBal());
		pab.setAvailBal(pab.getAvailBal().subtract(rdMerchBal.getTranAmt()).setScale(2, RoundingMode.HALF_UP));
		rdMerchBal.setAfterAmt(pab.getAvailBal());
		Map<String, BigDecimal> companyPayAvailBal = pab.getCompanyPayAvailBal();
		if (companyPayAvailBal == null) {
			companyPayAvailBal = new HashMap<>();
			pab.setCompanyPayAvailBal(companyPayAvailBal);
		}
		BigDecimal singleAvailBal = companyPayAvailBal.get(order.getPayCompany() + RedisConstants.link_symbol +  order.getPayMerch() + RedisConstants.link_symbol + order.getOutChannel());
		if (singleAvailBal == null) {
			singleAvailBal = BigDecimal.ZERO;
		}
		companyPayAvailBal.put(order.getPayCompany() + RedisConstants.link_symbol +  order.getPayMerch() + RedisConstants.link_symbol + order.getOutChannel(),
				singleAvailBal.subtract(rdMerchBal.getTranAmt()).setScale(2, RoundingMode.HALF_UP));
	}
	
	/**
	 * @Description ????????????????????????
	 * @param order
	 * @param rdMerchBal
	 * @param pab
	 */
	private void payAcctAvailBalSubForQr(Order order, RecordMerchBalDO rdMerchBal, PayAcctBal pab) {
		rdMerchBal.setBeforeAmt(pab.getAvailBal());
		pab.setAvailBal(pab.getAvailBal().subtract(rdMerchBal.getTranAmt()).setScale(2, RoundingMode.HALF_UP));
		rdMerchBal.setAfterAmt(pab.getAvailBal());
		Map<String, BigDecimal> companyPayAvailBal = pab.getCompanyPayAvailBal();
		if (companyPayAvailBal == null) {
			companyPayAvailBal = new HashMap<>();
			pab.setCompanyPayAvailBal(companyPayAvailBal);
		}
		BigDecimal singleAvailBal = companyPayAvailBal.get(order.getMerchNo() + RedisConstants.link_symbol +  order.getOutChannel());
		if (singleAvailBal == null) {
			singleAvailBal = BigDecimal.ZERO;
		}
		companyPayAvailBal.put(order.getMerchNo() + RedisConstants.link_symbol +  order.getOutChannel(),
				singleAvailBal.subtract(rdMerchBal.getTranAmt()).setScale(2, RoundingMode.HALF_UP));
	}

	/**
	 * ????????????????????????
	 */
	@Override
	public RecordMerchBalDO balForMerchChargeAdd(MerchCharge merchCharge, BigDecimal amount, int feeType,
			int orderType) {
		RecordMerchBalDO rdMerchBal = PayService.initRdMerchChargeBal(merchCharge, feeType, orderType, ProfitLoss.profit.id());
		rdMerchBal.setCrtDate(DateUtil.getCurrentTimeInt());
		rdMerchBal.setTranAmt(amount);
		RLock merchLock = RedissonLockUtil.getBalMerchLock(merchCharge.getMerchNo());
		try {
			merchLock.lock();
			PayAcctBal pab = RedisUtil.getMerchBal(merchCharge.getMerchNo());
			rdMerchBal.setBeforeAmt(pab.getBalance());
			pab.setBalance(pab.getBalance().add(rdMerchBal.getTranAmt()).setScale(2, RoundingMode.HALF_UP));
			rdMerchBal.setAfterAmt(pab.getBalance());
			RedisUtil.setMerchBal(pab);
		} finally {
			merchLock.unlock();
		}
		return rdMerchBal;
	}

	
	/***
	 * ??????????????????????????????
	 */
	@Override
	public RecordMerchBalDO availBalForMerchChargeAdd(MerchCharge merchCharge, BigDecimal amount, int feeType, int orderType) {
		RecordMerchBalDO rdMerchBal = PayService.initRdMerchChargeBal(merchCharge, feeType, orderType, ProfitLoss.profit.id());
		rdMerchBal.setCrtDate(DateUtil.getCurrentTimeInt());
		rdMerchBal.setTranAmt(amount);
		RLock merchLock = RedissonLockUtil.getBalMerchLock(merchCharge.getMerchNo());
		try {
			merchLock.lock();
			PayAcctBal pab = RedisUtil.getMerchBal(merchCharge.getMerchNo());
			this.payAcctAvailBalAdd(merchCharge, rdMerchBal, pab);
			RedisUtil.setMerchBal(pab);
		} finally {
			merchLock.unlock();
		}
		return rdMerchBal;
	}

	/**
	 * @Description ??????????????????????????????
	 * @param merchCharge
	 * @param rdMerchBal
	 * @param pab
	 */
	private void payAcctAvailBalAdd(MerchCharge merchCharge, RecordMerchBalDO rdMerchBal, PayAcctBal pab) {
		rdMerchBal.setBeforeAmt(pab.getAvailBal());
		pab.setAvailBal(pab.getAvailBal().add(rdMerchBal.getTranAmt()).setScale(2, RoundingMode.HALF_UP));
		rdMerchBal.setAfterAmt(pab.getAvailBal());
		Map<String, BigDecimal> companyPayAvailBal = pab.getCompanyPayAvailBal();
		if (companyPayAvailBal == null) {
			companyPayAvailBal = new HashMap<>();
			pab.setCompanyPayAvailBal(companyPayAvailBal);
		}
		BigDecimal singleAvailBal = companyPayAvailBal.get(merchCharge.getMerchNo() + RedisConstants.link_symbol +  merchCharge.getOutChannel());
		if (singleAvailBal == null) {
			singleAvailBal = BigDecimal.ZERO;
		}
		companyPayAvailBal.put(merchCharge.getMerchNo() + RedisConstants.link_symbol + merchCharge.getOutChannel(),
				singleAvailBal.add(rdMerchBal.getTranAmt()).setScale(2, RoundingMode.HALF_UP));
		
	}


}
