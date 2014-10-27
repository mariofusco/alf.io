/**
 * This file is part of bagarino.
 *
 * bagarino is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * bagarino is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with bagarino.  If not, see <http://www.gnu.org/licenses/>.
 */
package io.bagarino.controller;

import com.stripe.exception.StripeException;

import io.bagarino.controller.decorator.SaleableTicketCategory;
import io.bagarino.manager.EventManager;
import io.bagarino.manager.StripeManager;
import io.bagarino.manager.TicketReservationManager;
import io.bagarino.manager.TicketReservationManager.NotEnoughTicketsException;
import io.bagarino.manager.system.Mailer;
import io.bagarino.model.Event;
import io.bagarino.model.Ticket;
import io.bagarino.model.TicketCategory;
import io.bagarino.model.TicketReservation;
import io.bagarino.model.TicketReservation.TicketReservationStatus;
import io.bagarino.model.modification.TicketReservationModification;
import io.bagarino.repository.EventRepository;
import io.bagarino.repository.TicketCategoryRepository;
import io.bagarino.repository.TicketRepository;
import lombok.Data;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.ValidationUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.ServletWebRequest;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static io.bagarino.util.MonetaryUtil.formatCents;
import static io.bagarino.util.OptionalWrapper.optionally;
import static java.util.Collections.emptyList;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;

@Controller
public class ReservationController {
	
	private final EventRepository eventRepository;
    private final EventManager eventManager;
    private final TicketRepository ticketRepository;
    private final TicketReservationManager tickReservationManager;
    private final TicketCategoryRepository ticketCategoryRepository;
    private final StripeManager stripeManager;
    private final Mailer mailer;
    //
    private final EventController eventController;
    
    @Autowired
    public ReservationController(EventRepository eventRepository,
                                 EventManager eventManager,
			                     TicketRepository ticketRepository,
                                 TicketReservationManager tickReservationManager,
                                 TicketCategoryRepository ticketCategoryRepository,
                                 StripeManager stripeManager,
                                 Mailer mailer,
                                 EventController eventController) {
        this.eventRepository = eventRepository;
        this.eventManager = eventManager;
		this.ticketRepository = ticketRepository;
		this.tickReservationManager = tickReservationManager;
		this.ticketCategoryRepository = ticketCategoryRepository;
        this.stripeManager = stripeManager;
        this.mailer = mailer;
        this.eventController = eventController;
	}
	
	@RequestMapping(value = "/event/{eventName}/reserve-tickets", method = {RequestMethod.POST, RequestMethod.GET})
	public String reserveTicket(@PathVariable("eventName") String eventName, @ModelAttribute ReservationForm reservation, BindingResult bindingResult, Model model, ServletWebRequest request) {
		
		Optional<Event> event = optionally(() -> eventRepository.findByShortName(eventName));
		if(!event.isPresent()) {
    		return "redirect:/";
    	}
		
		if (request.getHttpMethod() == HttpMethod.GET) {
			return "redirect:/event/" + eventName + "/";
		}
		
		reservation.validate(bindingResult, tickReservationManager, ticketCategoryRepository, eventManager);
		
		if (bindingResult.hasErrors()) {
			model.addAttribute("error", bindingResult).addAttribute("hasErrors", bindingResult.hasErrors());//TODO: refactor
			return eventController.showEvent(eventName, model);
		}
			
		Date expiration = DateUtils.addMinutes(new Date(), TicketReservationManager.RESERVATION_MINUTE);
		
		try {
			String reservationId = tickReservationManager.createTicketReservation(event.get().getId(),
					reservation.selected(), expiration);
			return "redirect:/event/" + eventName + "/reservation/" + reservationId;
		} catch (NotEnoughTicketsException nete) {
			bindingResult.reject(ErrorsCode.STEP_1_NOT_ENOUGH_TICKETS);
			model.addAttribute("error", bindingResult).addAttribute("hasErrors", bindingResult.hasErrors());//TODO: refactor
			return eventController.showEvent(eventName, model);
		}
	}



    @RequestMapping(value = "/event/{eventName}/reservation/{reservationId}", method = RequestMethod.GET)
	public String showReservationPage(@PathVariable("eventName") String eventName, @PathVariable("reservationId") String reservationId, 
			@RequestParam(value="confirmation-email-sent", required=false, defaultValue="false") boolean confirmationEmailSent,
			@RequestParam(value="ticket-email-sent", required=false, defaultValue="false") boolean ticketEmailSent,
			Model model) {
    	
    	Optional<Event> event = optionally(() -> eventRepository.findByShortName(eventName));
    	if(!event.isPresent()) {
    		return "redirect:/";
    	}

    	Optional<TicketReservation> reservation = tickReservationManager.findById(reservationId);

    	model.addAttribute("event", event.get());
    	model.asMap().putIfAbsent("hasErrors", false);
    	
    	if(!reservation.isPresent()) {
    		model.addAttribute("reservationId", reservationId);
    		return "/event/reservation-page-not-found";
    	} else if(reservation.get().getStatus() == TicketReservationStatus.PENDING) {
    		
    		int reservationCost = totalReservationCost(reservationId);
    		
    		model.addAttribute("summary", extractSummary(reservationId));
    		model.addAttribute("free", reservationCost == 0);
    		model.addAttribute("totalPrice", formatCents(reservationCost));
    		model.addAttribute("reservationId", reservationId);
    		model.addAttribute("reservation", reservation.get());
    		
    		if(reservationCost > 0) {
    			model.addAttribute("stripe_p_key", stripeManager.getPublicKey());
    		}
    		
    		return "/event/reservation-page";
    	} else if (reservation.get().getStatus() == TicketReservationStatus.COMPLETE ){
    		model.addAttribute("reservationId", reservationId);
    		model.addAttribute("reservation", reservation.get());
    		model.addAttribute("confirmationEmailSent", confirmationEmailSent);
    		model.addAttribute("ticketEmailSent", ticketEmailSent);
    		
    		
    		List<Ticket> tickets = ticketRepository.findTicketsInReservation(reservationId);
    		
    		model.addAttribute("ticketsByCategory", tickets.stream().collect(Collectors.groupingBy(Ticket::getCategoryId)).entrySet()
    					.stream().map((e) -> Pair.of(ticketCategoryRepository.getById(e.getKey()), e.getValue()))
    					.collect(Collectors.toList()));
    		model.addAttribute("ticketsAreAllAssigned", tickets.stream().allMatch(Ticket::getAssigned));
    		
    		return "/event/reservation-page-complete";
    	} else { //reservation status is in payment.
    		throw new IllegalStateException();//FIXME
    	}
	}

    @RequestMapping(value = "/event/{eventName}/reservation/{reservationId}", method = RequestMethod.POST)
    public String handleReservation(@PathVariable("eventName") String eventName, @PathVariable("reservationId") String reservationId, 
    								PaymentForm paymentForm, BindingResult bindingResult,
                                    Model model) throws StripeException {
    	
    	Optional<Event> event = optionally(() -> eventRepository.findByShortName(eventName));
    	if(!event.isPresent()) {
    		return "redirect:/";
    	}
    	
    	Optional<TicketReservation> ticketReservation = tickReservationManager.findById(reservationId);
    	
    	if(!ticketReservation.isPresent()) {
    		model.addAttribute("reservationId", reservationId);
    		return "/event/reservation-page-not-found";
    	}
    	
    	if(paymentForm.shouldCancelReservation()) {
    		tickReservationManager.cancelPendingReservation(reservationId);
			return "redirect:/event/" + eventName + "/";
    	}
    	
    	if(!ticketReservation.get().getValidity().after(new Date())) {
    		bindingResult.reject("ticket_reservation_no_more_valid");
    	}
    	
    	final int reservationCost = totalReservationCost(reservationId);
    	
    	//
    	paymentForm.validate(bindingResult, reservationCost);
    	//
    	
    	if (bindingResult.hasErrors()) {
			model.addAttribute("error", bindingResult).addAttribute("hasErrors", bindingResult.hasErrors());//TODO: refactor
			return showReservationPage(eventName, reservationId, false, false, model);
    	}
    	
    	
    	String email = paymentForm.getEmail(), fullName = paymentForm.getFullName(), billingAddress = paymentForm.getBillingAddress();
    	
    	if(reservationCost > 0) {
    		//transition to IN_PAYMENT, so we can keep track if we have a failure between the stripe payment and the completition of the reservation
    		tickReservationManager.transitionToInPayment(reservationId, email, fullName, billingAddress);
    		
    		try {
    			stripeManager.chargeCreditCard(paymentForm.getStripeToken(), reservationCost, event.get().getCurrency(), reservationId, email, fullName, billingAddress);
    		} catch(StripeException se) {
    			bindingResult.reject("payment_processor_error");
    			model.addAttribute("error", bindingResult).addAttribute("hasErrors", bindingResult.hasErrors());//TODO: refactor
    			return showReservationPage(eventName, reservationId, false, false, model);
    		}
    	}
        
        // we can enter here only if the reservation is done correctly
        tickReservationManager.completeReservation(reservationId, email, fullName, billingAddress);
        //
        
        //
        sendReservationCompleteEmail(tickReservationManager.findById(reservationId).orElseThrow(IllegalStateException::new));
        //

        return "redirect:/event/" + eventName + "/reservation/" + reservationId;
    }
    
    
    @RequestMapping(value = "/event/{eventName}/reservation/{reservationId}/re-send-email", method = RequestMethod.POST)
    public String reSendReservationConfirmationEmail(@PathVariable("eventName") String eventName, @PathVariable("reservationId") String reservationId) {
    	
    	Optional<Event> event = optionally(() -> eventRepository.findByShortName(eventName));
    	if(!event.isPresent()) {
    		return "redirect:/";
    	}
    	
    	Optional<TicketReservation> ticketReservation = tickReservationManager.findById(reservationId);
    	if(!ticketReservation.isPresent()) {
    		return "redirect:/event/" + eventName + "/";
    	}
    	
    	sendReservationCompleteEmail(ticketReservation.orElseThrow(IllegalStateException::new));
    	
    	return "redirect:/event/" + eventName + "/reservation/" + reservationId+"?confirmation-email-sent=true";
    }
    
    //TODO: complete, additionally, the mail should be sent asynchronously
    private void sendReservationCompleteEmail(TicketReservation reservation) {
    	mailer.send(reservation.getEmail(), "reservation complete :D", "here be link", Optional.of("here be link html"));
    }
    
    
    private static int totalFrom(List<Ticket> tickets) {
    	return tickets.stream().mapToInt(Ticket::getPaidPriceInCents).sum();
    }
    
    private int totalReservationCost(String reservationId) {
    	return totalFrom(ticketRepository.findTicketsInReservation(reservationId));
    }
    
    private List<SummaryRow> extractSummary(String reservationId) {
    	List<SummaryRow> summary = new ArrayList<>();
    	List<Ticket> tickets = ticketRepository.findTicketsInReservation(reservationId);
    	tickets.stream().collect(Collectors.groupingBy(Ticket::getCategoryId)).forEach((categoryId, ticketsByCategory) -> {
    		String categoryName = ticketCategoryRepository.getById(categoryId).getName();
    		summary.add(new SummaryRow(categoryName, formatCents(ticketsByCategory.get(0).getPaidPriceInCents()), ticketsByCategory.size(), formatCents(totalFrom(ticketsByCategory))));
    	});
    	return summary;
    } 
    
 // step 1 : choose tickets
    @Data
	public static class ReservationForm {

        private List<TicketReservationModification> reservation;

        private List<TicketReservationModification> selected() {
			return ofNullable(reservation).orElse(emptyList()).stream()
					.filter((e) -> e != null && e.getAmount() != null && e.getTicketCategoryId() != null && e.getAmount() > 0)
					.collect(toList());
		}
		
		private int selectionCount() {
			return selected().stream().mapToInt(TicketReservationModification::getAmount).sum();
		}
		
		private void validate(BindingResult bindingResult,
                              TicketReservationManager tickReservationManager,
                              TicketCategoryRepository ticketCategoryRepository,
                              EventManager eventManager) {
			int selectionCount = selectionCount();
			
			if(selectionCount <= 0) {
				bindingResult.reject(ErrorsCode.STEP_1_SELECT_AT_LEAST_ONE);
			}
			
			if(selectionCount >  tickReservationManager.maxAmountOfTickets()) {
				bindingResult.reject(ErrorsCode.STEP_1_OVER_MAXIMUM);//FIXME: we must display the maximum amount of tickets
			}

            final List<TicketReservationModification> selected = selected();
            final ZoneId eventZoneId = selected.stream().findFirst().map(r -> {
                TicketCategory tc = ticketCategoryRepository.getById(r.getTicketCategoryId());
                return eventManager.findEventByTicketCategory(tc).getZoneId();
            }).orElse(null);
            final ZonedDateTime now = ZonedDateTime.now(eventZoneId);
            selected.forEach((r) -> {

                TicketCategory tc = ticketCategoryRepository.getById(r.getTicketCategoryId());
                SaleableTicketCategory ticketCategory = new SaleableTicketCategory(tc, now, eventZoneId);

                if (!ticketCategory.getSaleable()) {
                    bindingResult.reject(ErrorsCode.STEP_1_TICKET_CATEGORY_MUST_BE_SALEABLE); // TODO add correct field
                }
                if (ticketCategory.isAccessRestricted()) {
                    bindingResult.reject(ErrorsCode.STEP_1_ACCESS_RESTRICTED); //
                }
            });
		}
	}
    
    // step 2 : payment/claim ticketss
    
    @Data
    public static class PaymentForm {
    	private String stripeToken;
        private String email;
        private String fullName;
        private String billingAddress;
        private Boolean cancelReservation;
        
        private void validate(BindingResult bindingResult, int reservationCost) {
        	
        	
			if (reservationCost > 0 && StringUtils.isBlank(stripeToken)) {
				bindingResult.reject("missing_stripe_token");
			}
			
			
			//TODO: check email/fullname length/billing address
			ValidationUtils.rejectIfEmptyOrWhitespace(bindingResult, "email", "email_missing");
			
			//email, fullname maxlength is 255
			//billing address maxlength is 2048
			
			if(email != null && !email.contains("@")) {
				bindingResult.rejectValue("email", "not_an_email");
			}
			
			ValidationUtils.rejectIfEmptyOrWhitespace(bindingResult, "fullName", "fullname_missing");
        }
        
        public Boolean shouldCancelReservation() {
        	return Optional.ofNullable(cancelReservation).orElse(false);
        }
    }
    
    @Data
    public static class SummaryRow {
    	private final String name;
    	private final String price;
    	private final int amount;
    	private final String subTotal;
    }

}
