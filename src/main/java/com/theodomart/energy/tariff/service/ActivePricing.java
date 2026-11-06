package com.theodomart.energy.tariff.service;

/** The landlord's price and the platform's VAT rate, both as they stood at one single moment. */
public record ActivePricing(TariffVersionView version, int vatBasisPoints) {

}
